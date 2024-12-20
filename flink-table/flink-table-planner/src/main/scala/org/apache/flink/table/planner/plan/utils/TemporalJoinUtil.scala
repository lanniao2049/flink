/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.table.planner.plan.utils

import org.apache.flink.table.api.ValidationException
import org.apache.flink.table.planner.calcite.FlinkTypeFactory
import org.apache.flink.table.planner.calcite.FlinkTypeFactory.{isProctimeIndicatorType, isRowtimeIndicatorType}
import org.apache.flink.table.planner.plan.nodes.exec.spec.JoinSpec
import org.apache.flink.table.planner.plan.nodes.logical.FlinkLogicalJoin
import org.apache.flink.table.runtime.types.PlannerTypeUtils
import org.apache.flink.util.Preconditions.checkState

import org.apache.calcite.rel.`type`.RelDataType
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.core.{JoinInfo, JoinRelType}
import org.apache.calcite.rex._
import org.apache.calcite.sql.`type`.{OperandTypes, ReturnTypes}
import org.apache.calcite.sql.{SqlFunction, SqlFunctionCategory, SqlKind}
import org.apache.calcite.sql.fun.SqlStdOperatorTable
import org.apache.calcite.util.mapping.IntPair

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.mutable

/** Utilities for temporal join. */
object TemporalJoinUtil {

  // ----------------------------------------------------------------------------------------
  //                          Temporal Join Condition Utilities
  // ----------------------------------------------------------------------------------------

  /**
   * [[TEMPORAL_JOIN_CONDITION]] is a specific join condition which correctly defines references to
   * rightTimeAttribute, rightPrimaryKeyExpression and leftTimeAttribute. The condition is used to
   * mark this is a temporal table join and ensure columns these expressions depends on will not be
   * pruned.
   *
   * The join key pair is necessary for temporal table join to ensure the condition will not be
   * pushed down.
   *
   * The rightTimeAttribute, rightPrimaryKeyExpression and leftTimeAttribute will be extracted from
   * the condition in physical phase.
   */
  val TEMPORAL_JOIN_CONDITION = new SqlFunction(
    "__TEMPORAL_JOIN_CONDITION",
    SqlKind.OTHER_FUNCTION,
    ReturnTypes.BOOLEAN_NOT_NULL,
    null,
    OperandTypes.or(
      /** ------------------------ Temporal table join condition ------------------------* */
      // right time attribute and primary key are required in event-time temporal table join,
      OperandTypes.sequence(
        "'(LEFT_TIME_ATTRIBUTE, RIGHT_TIME_ATTRIBUTE, PRIMARY_KEY, LEFT_KEY, RIGHT_KEY)'",
        OperandTypes.DATETIME,
        OperandTypes.DATETIME,
        OperandTypes.ANY,
        OperandTypes.ANY,
        OperandTypes.ANY
      ),
      // right primary key is required for processing-time temporal table join
      OperandTypes.sequence(
        "'(LEFT_TIME_ATTRIBUTE, PRIMARY_KEY, LEFT_KEY, RIGHT_KEY)'",
        OperandTypes.DATETIME,
        OperandTypes.ANY,
        OperandTypes.ANY,
        OperandTypes.ANY),
      /** ------------------ Temporal table function join condition ---------------------* */
      // Event-time temporal function join condition
      OperandTypes.sequence(
        "'(LEFT_TIME_ATTRIBUTE, RIGHT_TIME_ATTRIBUTE, PRIMARY_KEY)'",
        OperandTypes.DATETIME,
        OperandTypes.DATETIME,
        OperandTypes.ANY),
      // Processing-time temporal function join condition
      OperandTypes.sequence(
        "'(LEFT_TIME_ATTRIBUTE, PRIMARY_KEY)'",
        OperandTypes.DATETIME,
        OperandTypes.ANY)
    ),
    SqlFunctionCategory.SYSTEM)

  /**
   * Initial temporal condition used in rewrite phase of logical plan, this condition will be
   * replaced with [[TEMPORAL_JOIN_CONDITION]] after the primary key inferred.
   */
  val INITIAL_TEMPORAL_JOIN_CONDITION = new SqlFunction(
    "__INITIAL_TEMPORAL_JOIN_CONDITION",
    SqlKind.OTHER_FUNCTION,
    ReturnTypes.BOOLEAN_NOT_NULL,
    null,
    OperandTypes.or(
      // initial Event-time temporal table join condition, will fill PRIMARY_KEY later,
      OperandTypes.sequence(
        "'(LEFT_TIME_ATTRIBUTE, RIGHT_TIME_ATTRIBUTE, LEFT_KEY, RIGHT_KEY)'",
        OperandTypes.DATETIME,
        OperandTypes.DATETIME,
        OperandTypes.ANY,
        OperandTypes.ANY),
      // initial Processing-time temporal table join condition, will fill PRIMARY_KEY later,
      OperandTypes.sequence(
        "'(LEFT_TIME_ATTRIBUTE, LEFT_KEY, RIGHT_KEY)'",
        OperandTypes.DATETIME,
        OperandTypes.ANY,
        OperandTypes.ANY)
    ),
    SqlFunctionCategory.SYSTEM)

  val TEMPORAL_JOIN_LEFT_KEY = new SqlFunction(
    "__TEMPORAL_JOIN_LEFT_KEY",
    SqlKind.OTHER_FUNCTION,
    ReturnTypes.BOOLEAN_NOT_NULL,
    null,
    OperandTypes.ARRAY,
    SqlFunctionCategory.SYSTEM)

  val TEMPORAL_JOIN_RIGHT_KEY = new SqlFunction(
    "__TEMPORAL_JOIN_RIGHT_KEY",
    SqlKind.OTHER_FUNCTION,
    ReturnTypes.BOOLEAN_NOT_NULL,
    null,
    OperandTypes.ARRAY,
    SqlFunctionCategory.SYSTEM)

  val TEMPORAL_JOIN_CONDITION_PRIMARY_KEY = new SqlFunction(
    "__TEMPORAL_JOIN_CONDITION_PRIMARY_KEY",
    SqlKind.OTHER_FUNCTION,
    ReturnTypes.BOOLEAN_NOT_NULL,
    null,
    OperandTypes.ARRAY,
    SqlFunctionCategory.SYSTEM)

  private def makePrimaryKeyCall(
      rexBuilder: RexBuilder,
      rightPrimaryKeyExpression: Seq[RexNode]): RexNode = {
    rexBuilder.makeCall(TEMPORAL_JOIN_CONDITION_PRIMARY_KEY, rightPrimaryKeyExpression)
  }

  private def makeLeftJoinKeyCall(rexBuilder: RexBuilder, keyExpression: Seq[RexNode]): RexNode = {
    rexBuilder.makeCall(TEMPORAL_JOIN_LEFT_KEY, keyExpression)
  }

  private def makeRightJoinKeyCall(rexBuilder: RexBuilder, keyExpression: Seq[RexNode]): RexNode = {
    rexBuilder.makeCall(TEMPORAL_JOIN_RIGHT_KEY, keyExpression)
  }

  def makeProcTimeTemporalFunctionJoinConCall(
      rexBuilder: RexBuilder,
      leftTimeAttribute: RexNode,
      rightPrimaryKeyExpression: RexNode): RexNode = {
    rexBuilder.makeCall(
      TEMPORAL_JOIN_CONDITION,
      leftTimeAttribute,
      makePrimaryKeyCall(rexBuilder, Array(rightPrimaryKeyExpression)))
  }

  def makeRowTimeTemporalFunctionJoinConCall(
      rexBuilder: RexBuilder,
      leftTimeAttribute: RexNode,
      rightTimeAttribute: RexNode,
      rightPrimaryKeyExpression: RexNode): RexNode = {
    rexBuilder.makeCall(
      TEMPORAL_JOIN_CONDITION,
      leftTimeAttribute,
      rightTimeAttribute,
      makePrimaryKeyCall(rexBuilder, Array(rightPrimaryKeyExpression)))
  }

  def makeInitialRowTimeTemporalTableJoinCondCall(
      rexBuilder: RexBuilder,
      leftTimeAttribute: RexNode,
      rightTimeAttribute: RexNode,
      leftJoinKeyExpression: Seq[RexNode],
      rightJoinKeyExpression: Seq[RexNode]): RexNode = {
    rexBuilder.makeCall(
      INITIAL_TEMPORAL_JOIN_CONDITION,
      leftTimeAttribute,
      rightTimeAttribute,
      makeLeftJoinKeyCall(rexBuilder, leftJoinKeyExpression),
      makeRightJoinKeyCall(rexBuilder, rightJoinKeyExpression)
    )
  }

  def makeRowTimeTemporalTableJoinConCall(
      rexBuilder: RexBuilder,
      leftTimeAttribute: RexNode,
      rightTimeAttribute: RexNode,
      rightPrimaryKeyExpression: Seq[RexNode],
      leftJoinKeyExpression: Seq[RexNode],
      rightJoinKeyExpression: Seq[RexNode]): RexNode = {
    rexBuilder.makeCall(
      TEMPORAL_JOIN_CONDITION,
      leftTimeAttribute,
      rightTimeAttribute,
      makePrimaryKeyCall(rexBuilder, rightPrimaryKeyExpression),
      makeLeftJoinKeyCall(rexBuilder, leftJoinKeyExpression),
      makeRightJoinKeyCall(rexBuilder, rightJoinKeyExpression)
    )
  }

  def makeInitialProcTimeTemporalTableJoinConCall(
      rexBuilder: RexBuilder,
      leftTimeAttribute: RexNode,
      leftJoinKeyExpression: Seq[RexNode],
      rightJoinKeyExpression: Seq[RexNode]): RexNode = {
    rexBuilder.makeCall(
      INITIAL_TEMPORAL_JOIN_CONDITION,
      leftTimeAttribute,
      makeLeftJoinKeyCall(rexBuilder, leftJoinKeyExpression),
      makeRightJoinKeyCall(rexBuilder, rightJoinKeyExpression)
    )
  }

  def makeProcTimeTemporalTableJoinConCall(
      rexBuilder: RexBuilder,
      leftTimeAttribute: RexNode,
      rightPrimaryKeyExpression: Seq[RexNode],
      leftJoinKeyExpression: Seq[RexNode],
      rightJoinKeyExpression: Seq[RexNode]): RexNode = {
    rexBuilder.makeCall(
      TEMPORAL_JOIN_CONDITION,
      leftTimeAttribute,
      makePrimaryKeyCall(rexBuilder, rightPrimaryKeyExpression),
      makeLeftJoinKeyCall(rexBuilder, leftJoinKeyExpression),
      makeRightJoinKeyCall(rexBuilder, rightJoinKeyExpression)
    )
  }

  def isInitialRowTimeTemporalTableJoin(rexCall: RexCall): Boolean = {
    // (LEFT_TIME_ATTRIBUTE, RIGHT_TIME_ATTRIBUTE, LEFT_KEY, RIGHT_KEY)
    rexCall.getOperator == INITIAL_TEMPORAL_JOIN_CONDITION && rexCall.operands.length == 4
  }

  def isInitialProcTimeTemporalTableJoin(rexCall: RexCall): Boolean = {
    // (LEFT_TIME_ATTRIBUTE, LEFT_KEY, RIGHT_KEY)
    rexCall.getOperator == INITIAL_TEMPORAL_JOIN_CONDITION && rexCall.operands.length == 3
  }

  private def containsTemporalJoinCondition(condition: RexNode): Boolean = {
    var hasTemporalJoinCondition: Boolean = false
    condition.accept(new RexVisitorImpl[Void](true) {
      override def visitCall(call: RexCall): Void = {
        if (
          call.getOperator != TEMPORAL_JOIN_CONDITION &&
          call.getOperator != INITIAL_TEMPORAL_JOIN_CONDITION
        ) {
          super.visitCall(call)
        } else {
          hasTemporalJoinCondition = true
          null
        }
      }
    })
    hasTemporalJoinCondition
  }

  def containsInitialTemporalJoinCondition(condition: RexNode): Boolean = {
    var hasTemporalJoinCondition: Boolean = false
    condition.accept(new RexVisitorImpl[Void](true) {
      override def visitCall(call: RexCall): Void = {
        if (call.getOperator != INITIAL_TEMPORAL_JOIN_CONDITION) {
          super.visitCall(call)
        } else {
          hasTemporalJoinCondition = true
          null
        }
      }
    })
    hasTemporalJoinCondition
  }

  def isRowTimeJoin(joinSpec: JoinSpec): Boolean = {
    val nonEquiJoinRex = joinSpec.getNonEquiCondition().orElse(null)

    var rowtimeJoin: Boolean = false
    val visitor = new RexVisitorImpl[Unit](true) {
      override def visitCall(call: RexCall): Unit = {
        if (
          TemporalTableJoinUtil.isRowTimeTemporalTableJoinCondition(call) ||
          isRowTimeTemporalFunctionJoinCon(call)
        ) {
          rowtimeJoin = true
        } else {
          super.visitCall(call)
        }
      }
    }
    nonEquiJoinRex.accept(visitor)
    rowtimeJoin
  }

  def isRowTimeTemporalFunctionJoinCon(rexCall: RexCall): Boolean = {
    // (LEFT_TIME_ATTRIBUTE, RIGHT_TIME_ATTRIBUTE, PRIMARY_KEY)
    rexCall.getOperator == TEMPORAL_JOIN_CONDITION && rexCall.operands.length == 3
  }

  def isTemporalFunctionJoin(rexBuilder: RexBuilder, joinInfo: JoinInfo): Boolean = {
    val nonEquiJoinRex = joinInfo.getRemaining(rexBuilder)
    var isTemporalFunctionJoin: Boolean = false
    val visitor = new RexVisitorImpl[Unit](true) {
      override def visitCall(call: RexCall): Unit = {
        if (isTemporalFunctionCon(call)) {
          isTemporalFunctionJoin = true
        } else {
          super.visitCall(call)
        }
      }
    }
    nonEquiJoinRex.accept(visitor)
    isTemporalFunctionJoin
  }

  def isTemporalFunctionCon(rexCall: RexCall): Boolean = {
    // (LEFT_TIME_ATTRIBUTE, PRIMARY_KEY)
    // (LEFT_TIME_ATTRIBUTE, RIGHT_TIME_ATTRIBUTE, PRIMARY_KEY)
    rexCall.getOperator == TEMPORAL_JOIN_CONDITION &&
    (rexCall.operands.length == 2 || rexCall.operands.length == 3)
  }

  def validateTemporalFunctionCondition(
      call: RexCall,
      leftTimeAttribute: RexNode,
      rightTimeAttribute: Option[RexNode],
      rightPrimaryKey: Option[Array[RexNode]],
      rightKeysStartingOffset: Int,
      joinSpec: JoinSpec,
      textualRepresentation: String): Unit = {

    if (TemporalJoinUtil.isRowTimeTemporalFunctionJoinCon(call)) {

      validateTemporalFunctionPrimaryKey(
        rightKeysStartingOffset,
        rightPrimaryKey,
        joinSpec,
        textualRepresentation)

      if (!isRowtimeIndicatorType(rightTimeAttribute.get.getType)) {
        throw new ValidationException(
          s"Non rowtime timeAttribute [${rightTimeAttribute.get.getType}] " +
            s"used to create TemporalTableFunction")
      }
      if (!isRowtimeIndicatorType(leftTimeAttribute.getType)) {
        throw new ValidationException(
          s"Non rowtime timeAttribute [${leftTimeAttribute.getType}] " +
            s"passed as the argument to TemporalTableFunction")
      }
    } else {
      validateTemporalFunctionPrimaryKey(
        rightKeysStartingOffset,
        rightPrimaryKey,
        joinSpec,
        textualRepresentation)

      if (!isProctimeIndicatorType(leftTimeAttribute.getType)) {
        throw new ValidationException(
          s"Non processing timeAttribute [${leftTimeAttribute.getType}] " +
            s"passed as the argument to TemporalTableFunction")
      }
    }
  }

  private def validateTemporalFunctionPrimaryKey(
      rightKeysStartingOffset: Int,
      rightPrimaryKey: Option[Array[RexNode]],
      joinInfo: JoinSpec,
      textualRepresentation: String): Unit = {
    if (joinInfo.getRightKeys.length != 1) {
      throw new ValidationException(
        s"Only single column join key is supported. " +
          s"Found ${joinInfo.getRightKeys} in [$textualRepresentation]")
    }

    if (rightPrimaryKey.isEmpty || rightPrimaryKey.get.length != 1) {
      throw new ValidationException(
        s"Only single primary key is supported. " +
          s"Found $rightPrimaryKey in [$textualRepresentation]")
    }
    val pk = rightPrimaryKey.get(0)

    val rightJoinKeyInputReference = joinInfo.getRightKeys()(0) + rightKeysStartingOffset

    val rightPrimaryKeyInputReference = extractInputRef(pk, textualRepresentation)

    if (rightPrimaryKeyInputReference != rightJoinKeyInputReference) {
      throw new ValidationException(
        s"Join key [$rightJoinKeyInputReference] must be the same as " +
          s"temporal table's primary key [$pk] " +
          s"in [$textualRepresentation]")
    }
  }

  /**
   * Gets the join key pairs from left input field index to temporal table field index
   * @param joinInfo
   *   the join information of temporal table join
   * @param calcOnTemporalTable
   *   the calc programs on temporal table
   */
  def getTemporalTableJoinKeyPairs(
      joinInfo: JoinInfo,
      calcOnTemporalTable: Option[RexProgram]): Array[IntPair] = {
    val joinPairs = joinInfo.pairs().asScala.toArray
    calcOnTemporalTable match {
      case Some(program) =>
        // the target key of joinInfo is the calc output fields, we have to remapping to table here
        val keyPairs = new mutable.ArrayBuffer[IntPair]()
        joinPairs.map {
          p =>
            val calcSrcIdx = getIdenticalSourceField(program, p.target)
            if (calcSrcIdx != -1) {
              keyPairs += new IntPair(p.source, calcSrcIdx)
            }
        }
        keyPairs.toArray
      case None => joinPairs
    }
  }

  // this is highly inspired by Calcite's RexProgram#getSourceField(int)
  private def getIdenticalSourceField(rexProgram: RexProgram, outputOrdinal: Int): Int = {
    assert((outputOrdinal >= 0) && (outputOrdinal < rexProgram.getProjectList.size()))
    val project = rexProgram.getProjectList.get(outputOrdinal)
    var index = project.getIndex
    while (true) {
      var expr = rexProgram.getExprList.get(index)
      expr match {
        case call: RexCall if call.getOperator == SqlStdOperatorTable.IN_FENNEL =>
          // drill through identity function
          expr = call.getOperands.get(0)
        case call: RexCall if call.getOperator == SqlStdOperatorTable.CAST =>
          // drill through identity function
          val outputType = call.getType
          val inputType = call.getOperands.get(0).getType
          val isCompatible = PlannerTypeUtils.isInteroperable(
            FlinkTypeFactory.toLogicalType(outputType),
            FlinkTypeFactory.toLogicalType(inputType))
          expr = if (isCompatible) call.getOperands.get(0) else expr
        case _ =>
      }
      expr match {
        case ref: RexLocalRef => index = ref.getIndex
        case ref: RexInputRef => return ref.getIndex
        case _ => return -1
      }
    }
    -1
  }

  def extractInputRef(rexNode: RexNode, textualRepresentation: String): Int = {
    val inputReferenceVisitor = new InputRefVisitor
    rexNode.accept(inputReferenceVisitor)
    checkState(
      inputReferenceVisitor.getFields.length == 1,
      "Failed to find input reference in [%s]",
      textualRepresentation)
    inputReferenceVisitor.getFields.head
  }

  /**
   * Check whether input join node satisfy preconditions to convert into temporal join.
   *
   * @param join
   *   input join to analyze.
   * @return
   *   True if input join node satisfy preconditions to convert into temporal join, else false.
   */
  def satisfyTemporalJoin(join: FlinkLogicalJoin): Boolean = {
    satisfyTemporalJoin(join, join.getLeft, join.getRight)
  }

  def satisfyTemporalJoin(join: FlinkLogicalJoin, newLeft: RelNode, newRight: RelNode): Boolean = {
    if (!containsTemporalJoinCondition(join.getCondition)) {
      return false
    }
    val joinInfo = JoinInfo.of(newLeft, newRight, join.getCondition)
    if (isTemporalFunctionJoin(join.getCluster.getRexBuilder, joinInfo)) {
      // Temporal table function join currently only support INNER JOIN
      join.getJoinType match {
        case JoinRelType.INNER => true
        case _ => false
      }
    } else {
      // Temporal table join currently only support INNER JOIN and LEFT JOIN
      join.getJoinType match {
        case JoinRelType.INNER | JoinRelType.LEFT => true
        case _ => false
      }
    }
  }
}
