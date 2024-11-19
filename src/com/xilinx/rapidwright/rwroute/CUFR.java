/*
 *
 * Copyright (c) 2024 The Chinese University of Hong Kong.
 * Copyright (c) 2022-2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Wenhao Lin, The Chinese University of Hong Kong.
 *
 * This file is part of RapidWright.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.xilinx.rapidwright.rwroute;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.interchange.Interchange;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorBase;
import com.xilinx.rapidwright.timing.delayestimator.InterconnectInfo;
import com.xilinx.rapidwright.util.ParallelismTools;
import com.xilinx.rapidwright.util.RuntimeTracker;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 *  This is the implementation of the parallel structure Recursive Partitioning Ternary Tree (RPTT)
 *  in the solution of our team CUFR for the Runtime-First FPGA Interchange Routing Contest @ FPGA'24.
 *  More detailed descriptions of our methods are in the following paper:
 *
 *  Xinshi Zang, Wenhao Lin, Shiju Lin, Jinwei Liu and Evangeline F.Y. Young. An Open-Source Fast Parallel
 *  Routing Approach for Commercial FPGAs. In Proceedings of the Great Lakes Symposium on VLSI 2024.
 *
 *  Team CUFR improved the runtime performance of RWRoute through the following two aspects:
 *  1.  Implemented Recursive Partitioning Ternary Tree (RPTT), an enhanced tree-based parallel structure,
 *      enabling CUFR to perform multi-threaded routing compared to the single-threaded RWRoute, thereby
 *      improving the runtime.
 *
 *      The traditional bi-partition tree algorithm uses a cutline to bisect a partition, with connections
 *      crossing the cutlines placed in the corresponding tree nodes, and then recursively building sub-trees
 *      for two sub-partitions. During routing, it first sequetially routes the connections within a tree node,
 *      and then parallelly routes the two sub-trees.
 *
 *      It was observed that the traditional bi-partition tree has a small number of tree nodes in the layers
 *      closer to the tree root, with each node containing a large amount of connections to be sequentially
 *      routed. This makes it difficult to fully utilize the available threads during the early stage of the
 *      routing process. To address this issue, RPTT also builds a sub-tree for the connections crossing the
 *      cutline to achieve more parallelism, and this sub-tree is routed prior to the sub-trees of the two
 *      sub-partitions.
 *
 *  2.  Proposed Hybrid Updating Strategy (HUS) targeting on larger and more difficult routing cases.
 *      (this strategy has been integrated into RWRoute)
 */
public class CUFR extends RWRoute {
    /* A recursive partitioning ternary tree */
    private CUFRpartitionTree partitionTree;
    /** Timer to store partitioning runtime */
    private RuntimeTracker partitionTimer;
    /** A unique ConnectionState instance to be reused by each thread (shadows RWRoute.connectionState)
     *  (do not use ThreadLocal as the only way to have its values garbage collected is through calling
     *  ThreadLocal.remove() from the owning thread; this cannot be done elegantly when routing has finished) */
    private final Map<Thread,ConnectionState> connectionState;

    public CUFR(Design design, RWRouteConfig config) {
        super(design, config);
        connectionState = new ConcurrentHashMap<>();
    }

    public static class RouteNodeGraphCUFR extends RouteNodeGraph {
        public RouteNodeGraphCUFR(Design design, RWRouteConfig config) {
            super(design, config);
        }

        // Do not track createRnodeTime since it is meaningless when multithreading
        @Override
        protected void addCreateRnodeTime(long time) {}
    }

    public static class RouteNodeGraphCUFRTimingDriven extends RouteNodeGraphTimingDriven {
        public RouteNodeGraphCUFRTimingDriven(Design design, RWRouteConfig config, DelayEstimatorBase delayEstimator) {
            super(design, config, delayEstimator);
        }

        // Do not track createRnodeTime since it is meaningless when multithreading
        @Override
        protected void addCreateRnodeTime(long time) {}
    }

    @Override
    protected RouteNodeGraph createRouteNodeGraph() {
        if (config.isTimingDriven()) {
            /* An instantiated delay estimator that is used to calculate delay of routing resources */
            DelayEstimatorBase estimator = new DelayEstimatorBase<>(design.getDevice(), new InterconnectInfo(), config.isUseUTurnNodes(), 0);
            return new RouteNodeGraphCUFRTimingDriven(design, config, estimator);
        } else {
            return new RouteNodeGraphCUFR(design, config);
        }
    }

    @Override
    protected ConnectionState getConnectionState() {
        return connectionState.computeIfAbsent(Thread.currentThread(), (k) -> new ConnectionState());
    }

    @Override
    protected void initialize() {
        super.initialize();
        partitionTimer = routerTimer.createStandAloneRuntimeTracker("update partitioning");
    }

    @Override
    protected void printRoutingStatistics() {
        routerTimer.getRuntimeTracker("route wire nets").addChild(partitionTimer);
        RuntimeTracker routeConnectionsTimer = routerTimer.getRuntimeTracker("route connections");
        routeConnectionsTimer.setTime(routeConnectionsTimer.getTime() - partitionTimer.getTime());
        super.printRoutingStatistics();
    }

    /**
     * Parallel route a partition tree.
     */
    private void routePartitionTree(CUFRpartitionTree.PartitionTreeNode node) {
        assert(node != null);
        if (node.left == null && node.right == null) {
            assert(node.middle == null);
            super.routeIndirectConnections(node.connections);
        } else {
            assert(node.left != null && node.right != null);
            if (node.middle != null) {
                routePartitionTree(node.middle);
            }

            ParallelismTools.invokeAll(
                    () -> routePartitionTree(node.left),
                    () -> routePartitionTree(node.right)
            );
        }
    }

    @Override
    protected void routeIndirectConnections(Collection<Connection> connections) {
        boolean firstIteration = (routeIteration == 1);
        if (firstIteration || config.isEnlargeBoundingBox()) {
            partitionTimer.start();
            partitionTree = new CUFRpartitionTree(sortedIndirectConnections, design.getDevice().getColumns(), design.getDevice().getRows());
            partitionTimer.stop();
        }
        routePartitionTree(partitionTree.root);
    }

    /**
     * Routes a {@link Design} instance.
     * @param design The {@link Design} instance to be routed.
     * @param args An array of string arguments, can be null.
     * If null, the design will be routed in the full timing-driven routing mode with default a {@link RWRouteConfig} instance.
     * For more options of the configuration, please refer to the {@link RWRouteConfig} class.
     * @return Routed design.
     */
    public static Design routeDesignWithUserDefinedArguments(Design design, String[] args) {
        // Instantiates a RWRouteConfig Object and parses the arguments.
        // Uses the default configuration if basic usage only.
        RWRouteConfig config = new RWRouteConfig(args);
        return routeDesign(design, new CUFR(design, config));
    }

    /**
     * The main interface of {@link CUFR} that reads in a {@link Design} design
     * (DCP or FPGA Interchange), and parses the arguments for the
     * {@link RWRouteConfig} object of the router.
     *
     * @param args An array of strings that are used to create a
     *             {@link RWRouteConfig} object for the router.
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("USAGE: <input.dcp|input.phys> <output.dcp>");
            return;
        }
        // Reads the output directory and set the output design checkpoint file name
        String routedDCPfileName = args[1];

        CodePerfTracker t = new CodePerfTracker("CUFR", true);

        // Reads in a design and routes it
        String[] rwrouteArgs = Arrays.copyOfRange(args, 2, args.length);
        Design input = null;
        if (Interchange.isInterchangeFile(args[0])) {
            input = Interchange.readInterchangeDesign(args[0]);
        } else {
            input = Design.readCheckpoint(args[0]);
        }
        Design routed = routeDesignWithUserDefinedArguments(input, rwrouteArgs);

        // Writes out the routed design checkpoint
        routed.writeCheckpoint(routedDCPfileName,t);
        System.out.println("\nINFO: Wrote routed design\n " + routedDCPfileName + "\n");
    }
}
