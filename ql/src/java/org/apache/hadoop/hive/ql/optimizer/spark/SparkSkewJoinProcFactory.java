/**
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

package org.apache.hadoop.hive.ql.optimizer.spark;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.Context;
import org.apache.hadoop.hive.ql.exec.JoinOperator;
import org.apache.hadoop.hive.ql.exec.MapJoinOperator;
import org.apache.hadoop.hive.ql.exec.Operator;
import org.apache.hadoop.hive.ql.exec.ReduceSinkOperator;
import org.apache.hadoop.hive.ql.exec.SMBMapJoinOperator;
import org.apache.hadoop.hive.ql.exec.TableScanOperator;
import org.apache.hadoop.hive.ql.exec.Task;
import org.apache.hadoop.hive.ql.exec.TaskFactory;
import org.apache.hadoop.hive.ql.exec.spark.SparkTask;
import org.apache.hadoop.hive.ql.lib.Node;
import org.apache.hadoop.hive.ql.lib.NodeProcessor;
import org.apache.hadoop.hive.ql.lib.NodeProcessorCtx;
import org.apache.hadoop.hive.ql.optimizer.GenMapRedUtils;
import org.apache.hadoop.hive.ql.optimizer.physical.GenSparkSkewJoinProcessor;
import org.apache.hadoop.hive.ql.optimizer.physical.SkewJoinProcFactory;
import org.apache.hadoop.hive.ql.optimizer.physical.SparkMapJoinResolver;
import org.apache.hadoop.hive.ql.parse.ParseContext;
import org.apache.hadoop.hive.ql.parse.QBJoinTree;
import org.apache.hadoop.hive.ql.parse.SemanticException;
import org.apache.hadoop.hive.ql.parse.spark.GenSparkUtils;
import org.apache.hadoop.hive.ql.plan.BaseWork;
import org.apache.hadoop.hive.ql.plan.MapWork;
import org.apache.hadoop.hive.ql.plan.OperatorDesc;
import org.apache.hadoop.hive.ql.plan.PlanUtils;
import org.apache.hadoop.hive.ql.plan.ReduceWork;
import org.apache.hadoop.hive.ql.plan.SparkEdgeProperty;
import org.apache.hadoop.hive.ql.plan.SparkWork;
import org.apache.hadoop.hive.ql.plan.TableDesc;

import java.io.Serializable;
import java.util.List;
import java.util.Set;
import java.util.Stack;

/**
 * Spark-version of SkewJoinProcFactory.
 */
public class SparkSkewJoinProcFactory {
  private SparkSkewJoinProcFactory() {
    // prevent instantiation
  }

  public static NodeProcessor getDefaultProc() {
    return SkewJoinProcFactory.getDefaultProc();
  }

  public static NodeProcessor getJoinProc() {
    return new SparkSkewJoinJoinProcessor();
  }

  public static class SparkSkewJoinJoinProcessor implements NodeProcessor {

    @Override
    public Object process(Node nd, Stack<Node> stack, NodeProcessorCtx procCtx,
        Object... nodeOutputs) throws SemanticException {
      SparkSkewJoinResolver.SparkSkewJoinProcCtx context =
          (SparkSkewJoinResolver.SparkSkewJoinProcCtx) procCtx;
      Task<? extends Serializable> currentTsk = context.getCurrentTask();
      JoinOperator op = (JoinOperator) nd;
      ReduceWork reduceWork = context.getReducerToReduceWork().get(op);
      ParseContext parseContext = context.getParseCtx();
      if (!op.getConf().isFixedAsSorted() && currentTsk instanceof SparkTask
        && reduceWork != null && ((SparkTask) currentTsk).getWork().contains(reduceWork)
        && GenSparkSkewJoinProcessor.supportRuntimeSkewJoin(
          op, currentTsk, parseContext.getConf())) {
        // first we try to split the task
        splitTask((SparkTask) currentTsk, reduceWork, parseContext);
        GenSparkSkewJoinProcessor.processSkewJoin(op, currentTsk, reduceWork, parseContext);
      }
      return null;
    }
  }

  /**
   * If the join is not in a leaf ReduceWork, the spark task has to be split into 2 tasks.
   */
  private static void splitTask(SparkTask currentTask, ReduceWork reduceWork,
      ParseContext parseContext) throws SemanticException {
    SparkWork currentWork = currentTask.getWork();
    Set<Operator<? extends OperatorDesc>> reduceSinkSet =
        SparkMapJoinResolver.getOp(reduceWork, ReduceSinkOperator.class);
    if (currentWork.getChildren(reduceWork).size() == 1 && canSplit(currentWork)
      && reduceSinkSet.size() == 1) {
      ReduceSinkOperator reduceSink = (ReduceSinkOperator) reduceSinkSet.iterator().next();
      BaseWork childWork = currentWork.getChildren(reduceWork).get(0);
      SparkEdgeProperty originEdge = currentWork.getEdgeProperty(reduceWork, childWork);
      // disconnect the reduce work from its child. this should produce two isolated sub graphs
      currentWork.disconnect(reduceWork, childWork);
      // move works following the current reduce work into a new spark work
      SparkWork newWork =
          new SparkWork(parseContext.getConf().getVar(HiveConf.ConfVars.HIVEQUERYID));
      newWork.add(childWork);
      copyWorkGraph(currentWork, newWork, childWork, true);
      copyWorkGraph(currentWork, newWork, childWork, false);
      // remove them from current spark work
      for (BaseWork baseWork : newWork.getAllWorkUnsorted()) {
        currentWork.remove(baseWork);
        currentWork.getCloneToWork().remove(baseWork);
      }
      // create TS to read intermediate data
      Context baseCtx = parseContext.getContext();
      Path taskTmpDir = baseCtx.getMRTmpPath();
      Operator<? extends OperatorDesc> rsParent = reduceSink.getParentOperators().get(0);
      TableDesc tableDesc = PlanUtils.getIntermediateFileTableDesc(PlanUtils
          .getFieldSchemasFromRowSchema(rsParent.getSchema(), "temporarycol"));
      // this will insert FS and TS between the RS and its parent
      TableScanOperator tableScanOp = GenMapRedUtils.createTemporaryFile(
          rsParent, reduceSink, taskTmpDir, tableDesc, parseContext);
      // create new MapWork
      MapWork mapWork = PlanUtils.getMapRedWork().getMapWork();
      mapWork.setName("Map " + GenSparkUtils.getUtils().getNextSeqNumber());
      newWork.add(mapWork);
      newWork.connect(mapWork, childWork, originEdge);
      // setup the new map work
      String streamDesc = taskTmpDir.toUri().toString();
      if (GenMapRedUtils.needsTagging((ReduceWork) childWork)) {
        Operator<? extends OperatorDesc> childReducer = ((ReduceWork) childWork).getReducer();
        QBJoinTree joinTree = null;
        if (childReducer instanceof JoinOperator) {
          joinTree = parseContext.getJoinContext().get(childReducer);
        } else if (childReducer instanceof MapJoinOperator) {
          joinTree = parseContext.getMapJoinContext().get(childReducer);
        } else if (childReducer instanceof SMBMapJoinOperator) {
          joinTree = parseContext.getSmbMapJoinContext().get(childReducer);
        }
        if (joinTree != null && joinTree.getId() != null) {
          streamDesc = joinTree.getId() + ":$INTNAME";
        } else {
          streamDesc = "$INTNAME";
        }
        String origStreamDesc = streamDesc;
        int pos = 0;
        while (mapWork.getAliasToWork().get(streamDesc) != null) {
          streamDesc = origStreamDesc.concat(String.valueOf(++pos));
        }
      }
      GenMapRedUtils.setTaskPlan(taskTmpDir.toUri().toString(), streamDesc,
          tableScanOp, mapWork, false, tableDesc);
      // insert the new task between current task and its child
      @SuppressWarnings("unchecked")
      Task<? extends Serializable> newTask = TaskFactory.get(newWork, parseContext.getConf());
      List<Task<? extends Serializable>> childTasks = currentTask.getChildTasks();
      // must have at most one child
      if (childTasks != null && childTasks.size() > 0) {
        Task<? extends Serializable> childTask = childTasks.get(0);
        currentTask.removeDependentTask(childTask);
        newTask.addDependentTask(childTask);
      }
      currentTask.addDependentTask(newTask);
      newTask.setFetchSource(currentTask.isFetchSource());
    }
  }

  /**
   * Whether we can split at reduceWork. For simplicity, let's require each work can
   * have at most one child work. This may be relaxed by checking connectivity of the
   * work graph after disconnect the current reduce work from its child
   */
  private static boolean canSplit(SparkWork sparkWork) {
    for (BaseWork baseWork : sparkWork.getAllWorkUnsorted()) {
      if (sparkWork.getChildren(baseWork).size() > 1) {
        return false;
      }
    }
    return true;
  }

  /**
   * Copy a sub-graph from originWork to newWork.
   */
  private static void copyWorkGraph(SparkWork originWork, SparkWork newWork,
      BaseWork baseWork, boolean upWards) {
    if (upWards) {
      for (BaseWork parent : originWork.getParents(baseWork)) {
        newWork.add(parent);
        SparkEdgeProperty edgeProperty = originWork.getEdgeProperty(parent, baseWork);
        newWork.connect(parent, baseWork, edgeProperty);
        copyWorkGraph(originWork, newWork, parent, true);
      }
    } else {
      for (BaseWork child : originWork.getChildren(baseWork)) {
        newWork.add(child);
        SparkEdgeProperty edgeProperty = originWork.getEdgeProperty(baseWork, child);
        newWork.connect(baseWork, child, edgeProperty);
        copyWorkGraph(originWork, newWork, child, false);
      }
    }
  }
}
