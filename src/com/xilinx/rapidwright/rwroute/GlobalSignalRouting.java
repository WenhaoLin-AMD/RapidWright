/*
 *
 * Copyright (c) 2021 Ghent University.
 * Copyright (c) 2022-2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Yun Zhou, Ghent University.
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.ClockRegion;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.placer.blockplacer.Point;
import com.xilinx.rapidwright.placer.blockplacer.SmallestEnclosingCircle;
import com.xilinx.rapidwright.router.RouteNode;
import com.xilinx.rapidwright.router.RouteThruHelper;
import com.xilinx.rapidwright.router.UltraScaleClockRouting;

/**
 * A collection of methods for routing global signals, i.e. GLOBAL_CLOCK, VCC and GND.
 * Adapted from RapidWright APIs.
 */
public class GlobalSignalRouting {
    private static final HashSet<String> lutOutputPinNames;
    static {
        lutOutputPinNames = new HashSet<>();
        for (String cle : new String[]{"L", "M"}) {
            for (String pin : new String[]{"A", "B", "C", "D", "E", "F", "G", "H"}) {
                lutOutputPinNames.add("CLE_CLE_" + cle + "_SITE_0_" + pin + "_O");
                lutOutputPinNames.add("CLE_CLE_" + cle + "_SITE_0_" + pin + "MUX");
                // Versal
                for (int siteIndex = 0; siteIndex < 2; siteIndex++) {
                    lutOutputPinNames.add("CLE_SLICE" + cle + "_TOP_" + siteIndex + "_" + pin + "_O_PIN");
                }
            }
        }
    }

    /**
     * Routes a clk enable net with input data.
     * @param clk The net to be routed.
     * @param routesToSinkINTTiles A map storing routes from CLK_OUT to different INT tiles that
     * connect to sink pins of a global clock net.
     * @param device The target device needed to get routing path representation with nodes from names.
     * @param getNodeStatus Lambda for indicating the status of a Node: available, in-use (preserved
     *                      for same net as we're routing), or unavailable (preserved for other net).
     */
    public static void routeClkWithPartialRoutes(Net clk,
                                                 Map<String, List<String>> routesToSinkINTTiles,
                                                 Device device,
                                                 Function<Node, NodeStatus> getNodeStatus) {
        Map<String, List<Node>> dstINTtilePaths = getListOfNodesFromRoutes(device, routesToSinkINTTiles);
        // Not import path after HDSTR
        Set<PIP> clkPIPs = new HashSet<>();
        Map<String, RouteNode> horDistributionLines = new HashMap<>();

        for (List<Node> nodes : dstINTtilePaths.values()) {
            clkPIPs.addAll(RouterHelper.getPIPsFromNodes(nodes));

            Node hDistr = nodes.get(nodes.size() - 1);
            RouteNode hdistr = new RouteNode(hDistr.getTile(), hDistr.getWireIndex());
            horDistributionLines.put(getDominateClockRegionOfNode(hDistr), hdistr);
        }
        clk.setPIPs(clkPIPs);

        Map<RouteNode, List<SitePinInst>> lcbMappings = getLCBPinMappings(clk.getPins(), getNodeStatus);

        UltraScaleClockRouting.routeToLCBs(clk, getStartingPoint(horDistributionLines, device), lcbMappings.keySet());

        // route LCBs to sink pins
        UltraScaleClockRouting.routeLCBsToSinks(clk, lcbMappings, getNodeStatus);

        Set<PIP> clkPIPsWithoutDuplication = new HashSet<>(clk.getPIPs());
        clk.setPIPs(clkPIPsWithoutDuplication);
    }

    private static Map<ClockRegion, Set<RouteNode>> getStartingPoint(Map<String, RouteNode> crDistLines, Device dev) {
        Map<ClockRegion, Set<RouteNode>> startingPoints = new HashMap<>();
        for (Entry<String, RouteNode> crRouteNode : crDistLines.entrySet()) {
            String crName = crRouteNode.getKey();
            ClockRegion cr = dev.getClockRegion(crName);
            startingPoints.computeIfAbsent(cr, (k) -> new HashSet<>()).add(crRouteNode.getValue());
        }
        return startingPoints;
    }

    private static String getDominateClockRegionOfNode(Node node) {
        // This is needed because a HDISTR for clock region X3Y2 can have a base tile in clock region X2Y2,
        // observed with clock routing of the optical-flow design.
        Map<String, Integer> crCounts = new HashMap<>();
        for (Wire wire : node.getAllWiresInNode()) {
            ClockRegion cr = wire.getTile().getClockRegion();
            if (cr == null) {
                continue;
            }
            crCounts.merge(cr.getName(), 1, Integer::sum);
        }

        String dominate = null;
        int max = 0;
        for (Entry<String, Integer> crCount : crCounts.entrySet()) {
            String cr = crCount.getKey();
            Integer count = crCount.getValue();
            if (count > max) {
                max = count;
                dominate = cr;
            }
        }

        return dominate;
    }

    /**
     * Gets a list of nodes for each destination, e.g. each clock region or sink INT tile, based on a list of the node names.
     * @param device The target device.
     * @param routes The given routes consisting of node names.
     * @return A map storing a list of nodes for each destination.
     */
    private static Map<String, List<Node>> getListOfNodesFromRoutes(Device device, Map<String, List<String>> routes) {
        Map<String, List<Node>> dstPaths = new HashMap<>();
        for (Entry<String, List<String>> dstRoute : routes.entrySet()) {
            String dst = dstRoute.getKey();
            List<Node> pathNodes = new ArrayList<>();
            for (String nodeName : dstRoute.getValue()) {
                Node node = Node.getNode(nodeName, device);
                if (node != null) {
                    pathNodes.add(node);
                } else {
                    System.err.println("ERROR: Null Node found under name: " + nodeName);
                }
            }
            dstPaths.put(dst, pathNodes);
        }
        return dstPaths;
    }

    /**
     * Routes a clock net by dividing the target clock regions into two groups and routes to the two groups with different centroid nodes.
     * @param clk The clock to be routed.
     * @param device The design device.
     * @param getNodeStatus Lambda for indicating the status of a Node: available, in-use (preserved
     *                      for same net as we're routing), or unavailable (preserved for other net).
     */
    public static void symmetricClkRouting(Net clk, Device device, Function<Node,NodeStatus> getNodeStatus) {
        List<ClockRegion> clockRegions = getClockRegionsOfNet(clk);
        ClockRegion centroid = findCentroid(clk, device);

        List<ClockRegion> upClockRegions = new ArrayList<>();
        List<ClockRegion> downClockRegions = new ArrayList<>();
        // divides clock regions into two groups
        divideClockRegions(clockRegions, centroid, upClockRegions, downClockRegions);

        RouteNode clkRoutingLine = UltraScaleClockRouting.routeBUFGToNearestRoutingTrack(clk);// first HROUTE
        RouteNode centroidHRouteNode = UltraScaleClockRouting.routeToCentroid(clk, clkRoutingLine, centroid, true, true);

        RouteNode vrouteUp = null;
        RouteNode vrouteDown;
        // Two VROUTEs going up and down
        ClockRegion aboveCentroid = upClockRegions.isEmpty() ? null : centroid.getNeighborClockRegion(1, 0);
        if (aboveCentroid != null) {
            vrouteUp = UltraScaleClockRouting.routeToCentroid(clk, centroidHRouteNode, aboveCentroid, true, false);
        }
        vrouteDown = UltraScaleClockRouting.routeToCentroid(clk, centroidHRouteNode, centroid.getNeighborClockRegion(0, 0), true, false);

        List<RouteNode> upDownDistLines = new ArrayList<>();
        if (aboveCentroid != null) {
            List<RouteNode> upLines = UltraScaleClockRouting.routeToHorizontalDistributionLines(clk, vrouteUp, upClockRegions, false, getNodeStatus);
            if (upLines != null) upDownDistLines.addAll(upLines);
        }

        List<RouteNode> downLines = UltraScaleClockRouting.routeToHorizontalDistributionLines(clk, vrouteDown, downClockRegions, true, getNodeStatus);//TODO this is where the antenna node shows up
        if (downLines != null) upDownDistLines.addAll(downLines);

        Map<RouteNode, List<SitePinInst>> lcbMappings = getLCBPinMappings(clk.getPins(), getNodeStatus);
        UltraScaleClockRouting.routeDistributionToLCBs(clk, upDownDistLines, lcbMappings.keySet());

        UltraScaleClockRouting.routeLCBsToSinks(clk, lcbMappings, getNodeStatus);

        Set<PIP> clkPIPsWithoutDuplication = new HashSet<>(clk.getPIPs());
        clk.setPIPs(clkPIPsWithoutDuplication);
    }

    /**
     * Gets clock regions of a net's sink pins.
     * @param clk The net in question.
     * @return A list of clock regions of the net's sink pins.
     */
    private static List<ClockRegion> getClockRegionsOfNet(Net clk) {
        List<ClockRegion> clockRegions = new ArrayList<>();
        for (SitePinInst pin : clk.getPins()) {
            if (pin.isOutPin()) continue;
            Tile t = pin.getTile();
            ClockRegion cr = t.getClockRegion();
            if (!clockRegions.contains(cr)) clockRegions.add(cr);
        }
        return clockRegions;
    }

    private static void divideClockRegions(List<ClockRegion> clockRegions, ClockRegion centroid, List<ClockRegion> upClockRegions,
            List<ClockRegion> downClockRegions) {
        for (ClockRegion cr : clockRegions) {
            if (cr.getInstanceY() > centroid.getInstanceY()) {
                upClockRegions.add(cr);
            } else {
                downClockRegions.add(cr);
            }
        }
    }

    /**
     * Maps each sink SitePinInsts of a clock net to a leaf clock buffer node.
     * @param clkPins List of clock pins in question.
     * @return A map between leaf clock buffer nodes and sink SitePinInsts.
     */
    public static Map<RouteNode, List<SitePinInst>> getLCBPinMappings(List<SitePinInst> clkPins,
                                                                      Function<Node,NodeStatus> getNodeStatus) {
        Map<RouteNode, List<SitePinInst>> lcbMappings = new HashMap<>();
        List<Node> lcbCandidates = new ArrayList<>();
        Set<Node> usedLcbs = new HashSet<>();
        for (SitePinInst p : clkPins) {
            if (p.isOutPin()) continue;
            assert(lcbCandidates.isEmpty());
            List<Node> intNodes = RouterHelper.projectInputPinToINTNode(p);
            if (intNodes == null || intNodes.isEmpty()) {
                throw new RuntimeException("Unable to get INT tile for pin " + p);
            }
            Node intNode = intNodes.get(0);

            outer: for (Node prev : intNode.getAllUphillNodes()) {
                NodeStatus prevNodeStatus = getNodeStatus.apply(prev);
                if (prevNodeStatus == NodeStatus.UNAVAILABLE) {
                    continue;
                }

                for (Node prevPrev : prev.getAllUphillNodes()) {
                    if (prevPrev.getIntentCode() != IntentCode.NODE_GLOBAL_LEAF) {
                        continue;
                    }

                    NodeStatus prevPrevNodeStatus = getNodeStatus.apply(prevPrev);
                    if (prevPrevNodeStatus == NodeStatus.UNAVAILABLE) {
                        continue;
                    }

                    if (usedLcbs.contains(prevPrev) || prevPrevNodeStatus == NodeStatus.INUSE) {
                        lcbCandidates.clear();
                        lcbCandidates.add(prevPrev);
                        break outer;
                    }

                    assert(prevPrevNodeStatus == NodeStatus.AVAILABLE);
                    lcbCandidates.add(prevPrev);
                }
            }

            if (lcbCandidates.isEmpty()) {
                throw new RuntimeException("ERROR: No mapped LCB to SitePinInst " + p);
            }
            Node n = lcbCandidates.get(0);
            RouteNode rn = new RouteNode(n.getTile(), n.getWireIndex());
            lcbMappings.computeIfAbsent(rn, (k) -> new ArrayList<>()).add(p);
            usedLcbs.add(n);
            lcbCandidates.clear();
        }

        return lcbMappings;
    }

    /**
     * Finds the centroid clock region of a clock net.
     * @param clk The clock net of a design.
     * @param device The device of the design.
     * @return The centroid clock region of a clock net.
     */
    private static ClockRegion findCentroid(Net clk, Device device) {
        HashSet<Point> sitePinInstTilePoints = new HashSet<>();
        for (SitePinInst spi : clk.getPins()) {
            if (spi.isOutPin()) continue;
            ClockRegion c = spi.getTile().getClockRegion();
            sitePinInstTilePoints.add(new Point(c.getColumn(),c.getRow()));
        }
        Point center = SmallestEnclosingCircle.getCenterPoint(sitePinInstTilePoints);
        return device.getClockRegion(center.y, center.x);
    }

    /**
     * Routes a static net (GND or VCC).
     * @param currNet The current static net to be routed.
     * @param getNodeState Lambda to get a node's status (available, unavailable, already in-use).
     * @param design The {@link Design} instance to use.
     * @param routeThruHelper The {@link RouteThruHelper} instance to use.
     */
    public static void routeStaticNet(Net currNet,
                                      Function<Node,NodeStatus> getNodeState,
                                      Design design, RouteThruHelper routeThruHelper) {
        NetType netType = currNet.getType();
        Set<PIP> netPIPs = new HashSet<>(currNet.getPIPs());
        Queue<LightweightRouteNode> q = new LinkedList<>();
        Set<LightweightRouteNode> visitedRoutingNodes = new HashSet<>();
        Set<LightweightRouteNode> usedRoutingNodes = new HashSet<>();
        Map<Node, LightweightRouteNode> createdRoutingNodes = new HashMap<>();

        String debugPinName = "IN DSP_X4Y143.CEALUMODE";
        List<String> vivadoPath = new ArrayList<>(List.of(
            "DSP_ROCF_B_TILE_X36Y290/DSP_DSP58_3_CEALUMODE_PIN",
            "INTF_ROCF_BR_TILE_X36Y291/INTF_IRI_QUADRANT_GREEN_3_CE3_O",
            "INTF_ROCF_BR_TILE_X36Y291/INTF_IRI_QUADRANT_GREEN_3_CE3_O_PIN",
            "INTF_ROCF_BR_TILE_X36Y291/INTF_IRI_QUADRANT_GREEN_3_CE3_PIN",
            "INTF_ROCF_BR_TILE_X36Y291/INTF_IRI_QUADRANT_GREEN_3_CE3",
            "INTF_ROCF_BR_TILE_X36Y291/INTF_CNODE_ATOM_9_INT_OUT1",
            "INT_X36Y291/OUT_EE2_W_BEG1",
            "INT_X36Y291/INT_NODE_SDQ_ATOM_93_INT_OUT0",
            "INT_X36Y295/OUT_SS4_W_BEG1",
            "INT_X36Y295/INT_NODE_SDQ_ATOM_91_INT_OUT1",
            "CLE_E_CORE_X36Y295/CLE_SLICEL_TOP_0_B_O",
            "CLE_E_CORE_X36Y295/CLE_SLICEL_TOP_0_B_O_PIN"
        ));

        // String debugPinName = "IN RAMB36_X3Y54.WEBWEU_2_";
        // List<String> vivadoPath = new ArrayList<>(List.of(
        //     "BRAM_ROCF_BR_TILE_X33Y220/BRAM_CORE_3_WEBWEU_2__PIN",
        //     "INTF_ROCF_BR_TILE_X33Y222/INTF_IRI_QUADRANT_GREEN_1_IMUX_O11",
        //     "INTF_ROCF_BR_TILE_X33Y222/INTF_IRI_QUADRANT_GREEN_1_IMUX_O11_PIN",
        //     "INTF_ROCF_BR_TILE_X33Y222/INTF_IRI_QUADRANT_GREEN_1_IMUX_IN11_PIN",
        //     "INT_X33Y222/IMUX_B_E23",
        //     "INT_X33Y222/INT_NODE_IMUX_ATOM_74_INT_OUT1",
        //     "INTF_ROCF_BR_TILE_X33Y222/IF_INT_BNODE_OUTS53",
        //     "INT_X33Y222/BOUNCE_E28",
        //     "INT_X33Y222/INT_NODE_IMUX_ATOM_27_INT_OUT0",
        //     "INT_X33Y226/OUT_SS4_E_BEG7",
        //     "INT_X33Y226/INT_NODE_SDQ_ATOM_74_INT_OUT0",
        //     "INT_X33Y227/OUT_SS1_E_BEG15",
        //     "INT_X33Y228/OUT_ENODE_E_0",
        //     "INT_X33Y228/INT_SDQ_RED_ATOM_16_INT_OUT0",
        //     "INT_X33Y228/INT_NODE_SDQ_ATOM_29_INT_OUT0",
        //     "CLE_E_CORE_X33Y228/CLE_SLICEL_TOP_0_C_O",
        //     "CLE_E_CORE_X33Y228/CLE_SLICEL_TOP_0_C_O_PIN"
        // ));

        HashMap<Node, Integer> node2FirstPopWatchDog = new HashMap<>();
        HashMap<Node, Integer> node2LastPopWatchDog = new HashMap<>();

        boolean findDebugPin = false;

        boolean debug = false;
        if (debug) {
            System.out.println("Net: " + currNet.getName());
        }

        Set<SitePin> sitePinsToCreate = new HashSet<>();
        for (SitePinInst sink : currNet.getPins()) {
            findDebugPin = false;
            if (sink.toString().equals(debugPinName)) {
                findDebugPin = true;
                System.out.println("Found " + debugPinName);
            }

            if (sink.isRouted()) continue;
            if (sink.isOutPin()) continue;
            int watchdog = 50000;
            if (debug) {
                System.out.println("SINK: TILE = " + sink.getTile().getName() + " NODE = " + sink.getConnectedNode().toString());
            }
            q.clear();
            visitedRoutingNodes.clear();
            List<Node> pathNodes = new ArrayList<>();
            Node node = sink.getConnectedNode();
            if (debug) System.out.println(node);
            LightweightRouteNode sinkRNode = RouterHelper.createRoutingNode(node, createdRoutingNodes);
            sinkRNode.setPrev(null);
            q.add(sinkRNode);
            boolean success = false;
            while (!q.isEmpty()) {
                LightweightRouteNode routingNode = q.poll();

                if (findDebugPin) {
                    if (vivadoPath.contains(routingNode.getNode().toString())) {
                        System.out.println(String.format("%-70s", routingNode.getNode().toString()) + " pop with watchdog: " + watchdog);
                    }
                    
                    node2LastPopWatchDog.put(routingNode.getNode(), watchdog);
                    if (!node2FirstPopWatchDog.containsKey(routingNode.getNode())) {
                        node2FirstPopWatchDog.put(routingNode.getNode(), watchdog);
                    }
                }

                visitedRoutingNodes.add(routingNode);
                if (debug) System.out.println("DEQUEUE:" + routingNode);
                if (debug) System.out.println(", PREV = " + routingNode.getPrev() == null ? " null" : routingNode.getPrev());
                if (success = isThisOurStaticSource(design, routingNode, netType, usedRoutingNodes)) {
                    //trace back for a complete path
                    if (debug) {
                        System.out.println("SINK: TILE = " + sink.getTile().getName() + " NODE = " + sink.getConnectedNode().toString());
                        System.out.println("SOURCE " + routingNode.toString() + " found");
                    }
                    while (routingNode != null) {
                        usedRoutingNodes.add(routingNode);// use routed RNodes as the source
                        pathNodes.add(routingNode.getNode());

                        if (debug) System.out.println("  " + routingNode.toString());
                        routingNode = routingNode.getPrev();
                    }

                    // Note that the static net router goes backward from sinks to sources,
                    // requiring the srcToSinkOrder parameter to be set to true below
                    netPIPs.addAll(RouterHelper.getPIPsFromNodes(pathNodes, true));

                    // If the source is an output site pin, put it aside for consideration
                    // to add as a new source pin
                    Node sourceNode = pathNodes.get(0);
                    if (((currNet.getType() == NetType.GND && !sourceNode.isTiedToGnd()) ||
                            (currNet.getType() == NetType.VCC && !sourceNode.isTiedToVcc()))) {
                        SitePin sitePin = sourceNode.getSitePin();
                        if (sitePin != null && !sitePin.isInput()) {
                            sitePinsToCreate.add(sitePin);
                        }
                    }

                    if (debug) {
                        for (Node pathNode:pathNodes) {
                            System.out.println(pathNode.toString());
                        }
                    }

                    if (findDebugPin) {
                        System.out.println("\n---------------------------Our Path--------------------------------\n");
                        for (Node pathNode: pathNodes) {
                            System.out.println(String.format("%-70s", pathNode.toString()) + " first pop: " + node2FirstPopWatchDog.get(pathNode) + " last pop: " + node2LastPopWatchDog.get(pathNode));
                        }
                    }

                    break;
                }
                if (debug) {
                    System.out.println("KEEP LOOKING FOR A SOURCE...");
                }
                for (Node uphillNode : routingNode.getNode().getAllUphillNodes()) {
                    boolean shouldSkipThisRouteThru = routeThruHelper.isRouteThru(uphillNode, routingNode.getNode()) && 
                                                      routingNode.getNode().getIntentCode() != IntentCode.NODE_IRI &&
                                                      routingNode.getNode().getTile().getTileTypeEnum() != TileTypeEnum.BLI_CLE_BOT_CORE &&
                                                      routingNode.getNode().getTile().getTileTypeEnum() != TileTypeEnum.BLI_CLE_BOT_CORE_MY;
                    if (shouldSkipThisRouteThru) continue;
                    LightweightRouteNode nParent = RouterHelper.createRoutingNode(uphillNode, createdRoutingNodes);
                    if (!pruneNode(nParent, getNodeState, visitedRoutingNodes, usedRoutingNodes)) {
                        nParent.setPrev(routingNode);
                        q.add(nParent);
                    }
                }
                watchdog--;
                if (watchdog < 0) {
                    break;
                }
            }
            if (!success) {
                System.err.println("ERROR: Failed to route " + currNet.getName() + " pin " + sink);
            } else {
                sink.setRouted(true);
            }
        }

        for (SitePin sitePin : sitePinsToCreate) {
            Site site = sitePin.getSite();
            SiteInst si = design.getSiteInstFromSite(site);
            String pinName = sitePin.getPinName();
            if (si == null) {
                // Create a dummy TIEOFF SiteInst
                String name = SiteInst.STATIC_SOURCE + "_" + site.getName();
                si = new SiteInst(name, site.getSiteTypeEnum());
                si.place(site);
                // Ensure it is not attached to the design
                assert (si.getDesign() == null);
            } else {
                SitePinInst spi = si.getSitePinInst(pinName);
                if (spi != null) {
                    if (spi.getNet() == currNet) {
                        continue;
                    }
                    throw new RuntimeException("ERROR: Site pin " + spi.getSitePinName() + " cannot be attached to " +
                            "net '" + currNet.getName() + "' as it's already connected to " +
                            "net '" + spi.getNet().getName() + "'");
                }
            }
            SitePinInst spi = new SitePinInst(pinName, si);
            boolean updateSiteRouting = false;
            currNet.addPin(spi, updateSiteRouting);
            spi.setRouted(true);
        }

        currNet.setPIPs(netPIPs);
    }

    /**
     * Checks if a {@link LightweightRouteNode} instance that represents a {@link Node} object should be pruned.
     * @param routingNode The RoutingNode in question.
     * @param getNodeStatus Lambda for indicating the status of a Node: available, in-use (preserved
     *                      for same net as we're routing), or unavailable (preserved for other net).
     * @param visitedRoutingNodes RoutingNode instances that have been visited.
     * @return true, if the RoutingNode instance should not be considered as an available resource.
     */
    private static boolean pruneNode(LightweightRouteNode routingNode,
                                     Function<Node,NodeStatus> getNodeStatus,
                                     Set<LightweightRouteNode> visitedRoutingNodes,
                                     Set<LightweightRouteNode> usedRoutingNodes) {
        Node node = routingNode.getNode();
        IntentCode ic = node.getTile().getWireIntentCode(node.getWireIndex());
        switch(ic) {
            case NODE_GLOBAL_VDISTR:
            case NODE_GLOBAL_HROUTE:
            case NODE_GLOBAL_HDISTR:
            case NODE_HLONG:
            case NODE_VLONG:
            case NODE_GLOBAL_VROUTE:
            case NODE_GLOBAL_LEAF:
            case NODE_GLOBAL_BUFG:
                return true;
            default:
        }
        NodeStatus status = getNodeStatus.apply(node);
        if (status == NodeStatus.UNAVAILABLE) {
            return true;
        }
        if (status == NodeStatus.INUSE) {
            assert(!visitedRoutingNodes.contains(routingNode));
            usedRoutingNodes.add(routingNode);
            return false;
        }
        return visitedRoutingNodes.contains(routingNode);
    }
    /**
     * Determines if the given {@link LightweightRouteNode} instance that represents a {@link Node} instance can serve as our sink.
     * @param routingNode The {@link LightweightRouteNode} instance in question.
     * @param type The net type to designate the static source type.
     * @param usedRoutingNodes The used RoutingNode instances by of the given net type representing the VCC or GND net.
     * @return true if this sources is usable, false otherwise.
     */
    private static boolean isThisOurStaticSource(Design design, LightweightRouteNode routingNode, NetType type, Set<LightweightRouteNode> usedRoutingNodes) {
        if (usedRoutingNodes != null && usedRoutingNodes.contains(routingNode))
            return true;
        Node node = routingNode.getNode();
        return isNodeUsableStaticSource(node, type, design);
    }

    /**
     * This method handles queries during the static source routing process.
     * It determines if the node in question can be used as a source for the current NetType.
     * @param node The node in question.
     * @param type The {@link NetType} instance to indicate what kind of static source we need (GND/VCC).
     * @param design The design instance to use for getting corresponding {@link SiteInst} instance info.
     * @return True if the pin is a hard source or an unused LUT output that can be repurposed as a source.
     */
    private static boolean isNodeUsableStaticSource(Node node, NetType type, Design design) {
        // We should look for 3 different potential sources
        // before we stop:
        // (1) GND_WIRE
        // (2) VCC_WIRE
        // (3) Unused LUT Outputs ([A-H]_O, [A-H]MUX)
        if ((type == NetType.VCC && node.isTiedToVcc()) ||
                (type == NetType.GND && node.isTiedToGnd())) {
            return true;
        }
        String wireName = node.getWireName();
        if (lutOutputPinNames.contains(wireName)) {
            boolean isVersal = design.getDevice().getSeries() == Series.Versal;
            int siteIndex = isVersal ? wireName.charAt(wireName.length() - 9) - '0' : 0;
            Site slice = node.getTile().getSites()[siteIndex];
            SiteInst si = design.getSiteInstFromSite(slice);
            if (si == null) {
                // Site is not used
                return true;
            }

            String sitePinName;
            if (isVersal) {
                assert(wireName.endsWith("_O_PIN"));
                sitePinName = wireName.substring(wireName.length() - 7, wireName.length() - 4);
            } else {
                if (wireName.endsWith("_O")) {
                    sitePinName = wireName.substring(wireName.length() - 3);
                } else if (wireName.endsWith("MUX")) {
                    char lutLetter = wireName.charAt(wireName.length() - 4);
                    Net o6Net = si.getNetFromSiteWire(lutLetter + "_O");
                    if (o6Net != null && o6Net.getType() != type) {
                        // 6LUT is occupied; play it safe and do not consider fracturing as that can require modifying the intra-site routing
                        return false;
                    }
                    Net o5Net = si.getNetFromSiteWire(lutLetter + "5LUT_O5");
                    if (o5Net != null && o5Net.getType() != type) {
                        // 5LUT is occupied
                        return false;
                    }

                    sitePinName = wireName.substring(wireName.length() - 4);
                } else {
                    throw new RuntimeException(wireName);
                }
            }
            Net sitePinNet = si.getNetFromSiteWire(sitePinName);
            return sitePinNet == null || sitePinNet.getType() == type;
        }
        return false;
    }

}
