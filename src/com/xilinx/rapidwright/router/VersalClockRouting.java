package com.xilinx.rapidwright.router;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.ClockRegion;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.rwroute.GlobalSignalRouting;
import com.xilinx.rapidwright.rwroute.NodeStatus;
import com.xilinx.rapidwright.rwroute.RouterHelper;
import com.xilinx.rapidwright.router.RouteThruHelper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A collection of utility methods for routing clocks on
 * the UltraScale architecture.
 *
 * Created on: Feb 1, 2018
 */
public class VersalClockRouting {

    public static RouteNode routeBUFGToNearestRoutingTrack(Net clk) {
        Queue<RouteNode> q = new LinkedList<>();
        q.add(new RouteNode(clk.getSource()));
        int watchDog = 300;
        while (!q.isEmpty()) {
            RouteNode curr = q.poll();
            Node currNode = Node.getNode(curr);
            // IntentCode c = curr.getIntentCode();
            IntentCode c = currNode.getIntentCode();
            if (c == IntentCode.NODE_GLOBAL_HROUTE_HSR) {
                // clk.getPIPs().addAll(curr.getPIPsBackToSource());
                clk.getPIPs().addAll(curr.getPIPsBackToSourceByNodes());
                return curr;
            }
            // for (Wire w : curr.getWireConnections()) {
            //     q.add(new RouteNode(w.getTile(), w.getWireIndex(), curr, curr.getLevel()+1));
            // }
            for (Node downhill: currNode.getAllDownhillNodes()) {
                q.add(new RouteNode(downhill.getTile(), downhill.getWireIndex(), curr, curr.getLevel()+1));
            }
            if (watchDog-- == 0) break;
        }
        return null;
    }

    /**
     * Routes a clock from a routing track to a transition point called the centroid
     * where the clock fans out and transitions from clock routing tracks to clock distribution
     * tracks
     * @param clk The current clock net to contribute routing
     * @param clkRoutingLine The intermediate start point of the clock route
     * @param centroid ClockRegion/FSR considered to be the centroid target
     */
    public static RouteNode routeToCentroid(Net clk, RouteNode clkRoutingLine, ClockRegion centroid) {
        return routeToCentroid(clk, clkRoutingLine, centroid, false, false);
    }
    /**
     * Routes a clock from a routing track to a transition point where the clock.
     * fans out and transitions from clock routing tracks to clock distribution.
     * @param clk The current clock net to contribute routing.
     * @param startingRouteNode The intermediate start point of the clock route.
     * @param clockRegion The center clock region or the clock region that is one row above or below the center.
     * @param adjusted A flag to guard the default functionality when routing to centroid clock region.
     * @param findCentroidHroute The flag to indicate the returned RouteNode should be HROUTE in the center or VROUTE going up or down.
     */
    public static RouteNode routeToCentroid(Net clk, RouteNode startingRouteNode, ClockRegion clockRegion, boolean adjusted, boolean findCentroidHroute) {
        Queue<RouteNode> q = RouteNode.createPriorityQueue();
        // HashSet<RouteNode> visited = new HashSet<>();
        startingRouteNode.setParent(null);
        q.add(startingRouteNode);
        Set<IntentCode> allowedIntentCodes = EnumSet.of(
            IntentCode.NODE_GLOBAL_GCLK,
            IntentCode.NODE_GLOBAL_HROUTE_HSR,
            IntentCode.NODE_GLOBAL_VROUTE,
            IntentCode.NODE_GLOBAL_VDISTR_LVL2
        );
        // Tile approxTarget = clockRegion.getApproximateCenter();
        int watchDog = 10000000;

        RouteNode centroidHRouteNode;

        Set<Node> visited = new HashSet<>();

        while (!q.isEmpty()) {
            RouteNode curr = q.poll();
            // visited.add(curr);

            // for (Wire w : curr.getWireConnections()) {
            //     RouteNode parent = curr.getParent();
            //     if (parent != null) {
            //         if (parent.getIntentCode() == IntentCode.NODE_GLOBAL_VROUTE &&
            //                 w.getIntentCode() == IntentCode.NODE_GLOBAL_HROUTE_HSR) {
            //             // Disallow ability to go from VROUTE back to HROUTE
            //             continue;
            //         }
            //         if (w.getIntentCode()     == IntentCode.NODE_GLOBAL_VDISTR_LVL2 &&
            //            curr.getIntentCode()   == IntentCode.NODE_GLOBAL_GCLK &&
            //            parent.getIntentCode() == IntentCode.NODE_GLOBAL_VROUTE &&
            //         //    clockRegion.equals(w.getTile().getClockRegion()) &&
            //            clockRegion.equals(curr.getTile().getClockRegion()) &&
            //            clockRegion.equals(parent.getTile().getClockRegion()) &&
            //            parent.getWireName().contains("BOT")) {
            //             if (adjusted) {
            //                 if (findCentroidHroute) {
            //                     centroidHRouteNode = curr.getParent();
            //                     while (centroidHRouteNode.getIntentCode() != IntentCode.NODE_GLOBAL_HROUTE_HSR) {
            //                         centroidHRouteNode = centroidHRouteNode.getParent();
            //                     }
            //                     clk.getPIPs().addAll(centroidHRouteNode.getPIPsBackToSource());
            //                     return centroidHRouteNode;
            //                 }
            //                 // assign PIPs based on which RouteNode returned, instead of curr
            //                 clk.getPIPs().addAll(parent.getPIPsBackToSource());
            //                 return parent;
            //             } else {
            //                 clk.getPIPs().addAll(curr.getPIPsBackToSource());
            //                 return curr;
            //             }
            //         }
            //     }

            //     // Only using routing lines to get to centroid
            //     if (!w.getIntentCode().isVersalClockRouting()) continue;
            //     if (adjusted && !findCentroidHroute && w.getIntentCode() == IntentCode.NODE_GLOBAL_HROUTE_HSR) {
            //         continue;
            //     }
            //     RouteNode rn = new RouteNode(w.getTile(), w.getWireIndex(), curr, curr.getLevel()+1);
            //     if (visited.contains(rn)) continue;
            //     ClockRegion rnClockRegion = rn.getTile().getClockRegion();
            //     int cost = Math.abs(rnClockRegion.getColumn() - clockRegion.getColumn()) + Math.abs(rnClockRegion.getRow() - clockRegion.getRow());
            //     rn.setCost(cost);
            //     q.add(rn);
            // }
            // if (watchDog-- == 0) {
            //     throw new RuntimeException("ERROR: Could not route from " + startingRouteNode + " to clock region " + clockRegion);
            // }

            Node currNode = Node.getNode(curr);
            RouteNode parent = curr.getParent();
            for (Node downhill : currNode.getAllDownhillNodes()) {
                if (parent != null) {
                    Node parentNode = Node.getNode(parent);
                    if (parentNode.getIntentCode() == IntentCode.NODE_GLOBAL_VROUTE &&
                        currNode.getIntentCode() == IntentCode.NODE_GLOBAL_HROUTE_HSR) {
                        // Disallow ability to go from VROUTE back to HROUTE
                        continue;
                    }
                    if (downhill.getIntentCode()     == IntentCode.NODE_GLOBAL_VDISTR_LVL2 &&
                       currNode.getIntentCode()   == IntentCode.NODE_GLOBAL_GCLK &&
                       parentNode.getIntentCode() == IntentCode.NODE_GLOBAL_VROUTE &&
                    //    clockRegion.equals(w.getTile().getClockRegion()) &&
                       clockRegion.equals(currNode.getTile().getClockRegion()) &&
                       clockRegion.equals(parentNode.getTile().getClockRegion()) &&
                       parentNode.getWireName().contains("BOT")) {
                        if (adjusted) {
                            if (findCentroidHroute) {
                                centroidHRouteNode = curr.getParent();
                                while (centroidHRouteNode.getIntentCode() != IntentCode.NODE_GLOBAL_HROUTE_HSR) {
                                    centroidHRouteNode = centroidHRouteNode.getParent();
                                }
                                // clk.getPIPs().addAll(centroidHRouteNode.getPIPsBackToSource());
                                clk.getPIPs().addAll(centroidHRouteNode.getPIPsBackToSourceByNodes());
                                return centroidHRouteNode;
                            }
                            // assign PIPs based on which RouteNode returned, instead of curr
                            // clk.getPIPs().addAll(parent.getPIPsBackToSource());
                            clk.getPIPs().addAll(parent.getPIPsBackToSourceByNodes());
                            return parent;
                        } else {
                            // clk.getPIPs().addAll(curr.getPIPsBackToSource());
                            clk.getPIPs().addAll(curr.getPIPsBackToSourceByNodes());
                            return curr;
                        }
                    }
                }

                // Only using routing lines to get to centroid
                // if (!downhill.getIntentCode().isVersalClockRouting()) continue;
                if (!allowedIntentCodes.contains(downhill.getIntentCode())) continue;
                if (adjusted && !findCentroidHroute && downhill.getIntentCode() == IntentCode.NODE_GLOBAL_HROUTE_HSR) {
                    continue;
                }
                if (visited.contains(downhill)) continue;
                RouteNode rn = new RouteNode(downhill.getTile(), downhill.getWireIndex(), curr, curr.getLevel()+1);
                
                ClockRegion rnClockRegion = rn.getTile().getClockRegion();
                int cost = Math.abs(rnClockRegion.getColumn() - clockRegion.getColumn()) + Math.abs(rnClockRegion.getRow() - clockRegion.getRow());
                rn.setCost(cost);
                q.add(rn);
                visited.add(downhill);
            }
            if (watchDog-- == 0) {
                throw new RuntimeException("ERROR: Could not route from " + startingRouteNode + " to clock region " + clockRegion);
            }
        }

        return null;
    }

    /**
     * Routes a clock from a routing track to a given transition point called the centroid
     * @param clk The clock net to be routed
     * @param startingRouteNode The starting routing track
     * @param centroid The given centroid node
     * @return
     */
    public static RouteNode routeToCentroidNode(Net clk, RouteNode startingRouteNode, Node centroid) {
        Queue<RouteNode> q = RouteNode.createPriorityQueue();
        HashSet<RouteNode> visited = new HashSet<>();

        startingRouteNode.setParent(null);
        q.add(startingRouteNode);
        Tile tileTarget = centroid.getTile();

        int watchDog = 10000;
        while (!q.isEmpty()) {
            RouteNode curr = q.poll();
            visited.add(curr);

            for (Wire w : curr.getWireConnections()) {
                // Only using clk routing network to reach centroid
                if (!w.getIntentCode().isUltraScaleClocking()) continue;

                if (w.getWireName().equals(centroid.getWireName())
                        && w.getTile().equals(centroid.getTile())) {
                    // curr is not the target, build the target
                    RouteNode routeNodeTarget = new RouteNode(w.getTile(), w.getWireIndex(), curr, curr.getLevel()+1);
                    clk.getPIPs().addAll(routeNodeTarget.getPIPsBackToSource());
                    return routeNodeTarget;
                }

                RouteNode rn = new RouteNode(w.getTile(), w.getWireIndex(), curr, curr.getLevel()+1);
                if (visited.contains(rn)) continue;
                // using column & row based distance is more accurate than tile x/y coordinate based distance
                int md = Math.abs(rn.getTile().getColumn() - tileTarget.getColumn()) + Math.abs(rn.getTile().getRow() - tileTarget.getRow());
                rn.setCost(md);
                q.add(rn);
            }
            if (watchDog-- == 0) {
                throw new RuntimeException("ERROR: Could not route from " + startingRouteNode + "\n       to the given centroid: " + centroid
                                            + ".\n       Please check if BUFGCE is correctly placed in line with the reference.");
            }
        }
        return null;
    }

    /**
     * Routes the centroid route track to a vertical distribution track to realize
     * the centroid and root of the clock.
     * @param clk Clock net to route
     * @param centroidRouteLine The current routing track found in the centroid
     * @return The vertical distribution track for the centroid clock region
     */
    public static RouteNode transitionCentroidToDistributionLine(Net clk, RouteNode centroidRouteLine) {
        centroidRouteLine.setParent(null);
        if (centroidRouteLine.getIntentCode() == IntentCode.NODE_GLOBAL_VDISTR) {
            return centroidRouteLine;
        }
        ClockRegion currCR = centroidRouteLine.getTile().getClockRegion();
        return transitionCentroidToDistributionLine(clk, centroidRouteLine, currCR);
    }

    public static RouteNode transitionCentroidToVerticalDistributionLine(Net clk, RouteNode centroidRouteLine, boolean down) {
        centroidRouteLine.setParent(null);
        if (centroidRouteLine.getIntentCode() == IntentCode.NODE_GLOBAL_VDISTR) {
            return centroidRouteLine;
        }

        ClockRegion currCR = centroidRouteLine.getTile().getClockRegion();
        if (down && currCR.getRow() > 0) {
            currCR = currCR.getNeighborClockRegion(-1, 0);
        }
        return transitionCentroidToDistributionLine(clk, centroidRouteLine, currCR);
    }

    public static RouteNode transitionCentroidToDistributionLine(Net clk, RouteNode centroidRouteLine, ClockRegion cr) {
        Queue<RouteNode> q = new LinkedList<>();
        q.add(centroidRouteLine);
        int watchDog = 100000;
        while (!q.isEmpty()) {
            RouteNode curr = q.poll();
            IntentCode c = curr.getIntentCode();
            if (curr.getTile().getClockRegion().equals(cr) && c == IntentCode.NODE_GLOBAL_VDISTR) {
                clk.getPIPs().addAll(curr.getPIPsBackToSource());
                return curr;
            }
            // if (c == IntentCode.NODE_GLOBAL_VDISTR) {
            //     clk.getPIPs().addAll(curr.getPIPsBackToSource());
            //     return curr;
            // }
            for (Wire w : curr.getWireConnections()) {
                // Stay in this clock region to transition from
                // if (!cr.equals(w.getTile().getClockRegion())) continue;
                if (!w.getIntentCode().isVersalClocking()) continue;
                q.add(new RouteNode(w.getTile(), w.getWireIndex(), curr, curr.getLevel()+1));
            }
            if (watchDog-- == 0) break;
        }
        return null;
    }

    /**
     * Routes the vertical distribution path and generates a map between each target clock region and the vertical distribution line to
     * start from.
     * @param clk The clock net.
     * @param centroidDistNode Starting point vertical distribution line
     * @param clockRegions The target clock regions.
     * @return A map of target clock regions and their respective vertical distribution lines
     */
    public static Map<ClockRegion, RouteNode> routeCentroidToVerticalDistributionLines(Net clk,
                                                                                       RouteNode centroidDistNode,
                                                                                       Collection<ClockRegion> clockRegions,
                                                                                       Function<Node, NodeStatus> getNodeStatus) {
        Map<ClockRegion, RouteNode> crToVdist = new HashMap<>();
        centroidDistNode.setParent(null);
        Queue<RouteNode> q = RouteNode.createPriorityQueue();
        HashSet<RouteNode> visited = new HashSet<>();
        Set<PIP> allPIPs = new HashSet<>();
        Set<RouteNode> startingPoints = new HashSet<>();
        startingPoints.add(centroidDistNode);
        assert(centroidDistNode.getParent() == null);
        nextClockRegion: for (ClockRegion cr : clockRegions) {
            q.clear();
            visited.clear();
            q.addAll(startingPoints);
            Tile crTarget = cr.getApproximateCenter();
            while (!q.isEmpty()) {
                RouteNode curr = q.poll();
                visited.add(curr);
                IntentCode c = curr.getIntentCode();
                ClockRegion currCR = curr.getTile().getClockRegion();
                if (currCR != null && cr.equals(currCR) && c == IntentCode.NODE_GLOBAL_VDISTR) {
                    // Only consider base wires
                    Node currNode = Node.getNode(curr);
                    if (getNodeStatus.apply(currNode) == NodeStatus.INUSE) {
                        startingPoints.add(curr);
                    } else {
                        List<PIP> pips = curr.getPIPsBackToSource();
                        allPIPs.addAll(pips);
                        for (PIP p : pips) {
                            startingPoints.add(p.getStartRouteNode());
                            startingPoints.add(p.getEndRouteNode());
                        }
                    }
                    RouteNode currBase = new RouteNode(currNode);
                    currBase.setParent(null);
                    crToVdist.put(cr, currBase);
                    continue nextClockRegion;
                }
                for (Wire w : curr.getWireConnections()) {
                    if (w.getIntentCode() != IntentCode.NODE_GLOBAL_VDISTR) continue;
                    Node n = Node.getNode(w);
                    RouteNode rn = new RouteNode(n.getTile(), n.getWireIndex(), curr, curr.getLevel()+1);
                    if (visited.contains(rn)) continue;
                    rn.setCost(w.getTile().getManhattanDistance(crTarget));
                    q.add(rn);
                }
            }
            throw new RuntimeException("ERROR: Couldn't route to distribution line in clock region " + cr);
        }
        clk.getPIPs().addAll(allPIPs);
        centroidDistNode.setParent(null);
        return crToVdist;
    }

    public static Map<ClockRegion, RouteNode> routeVrouteToVerticalDistributionLines(Net clk,
                                                                                       RouteNode vroute,
                                                                                       Collection<ClockRegion> clockRegions,
                                                                                       Function<Node, NodeStatus> getNodeStatus) {
        Map<ClockRegion, RouteNode> crToVdist = new HashMap<>();
        vroute.setParent(null);
        Queue<RouteNode> q = RouteNode.createPriorityQueue();
        HashSet<Node> visited = new HashSet<>();
        Set<PIP> allPIPs = new HashSet<>();
        Set<RouteNode> startingPoints = new HashSet<>();
        startingPoints.add(vroute);
        assert(vroute.getParent() == null);
        Set<IntentCode> allowedIntentCodes = EnumSet.of(
            // IntentCode.NODE_GLOBAL_VROUTE,
            IntentCode.NODE_GLOBAL_VDISTR,
            IntentCode.NODE_GLOBAL_VDISTR_LVL1,
            IntentCode.NODE_GLOBAL_VDISTR_LVL2,
            IntentCode.NODE_GLOBAL_GCLK
        );
        nextClockRegion: for (ClockRegion cr : clockRegions) {
            q.clear();
            visited.clear();
            q.addAll(startingPoints);
            Tile crTarget = cr.getApproximateCenter();
            while (!q.isEmpty()) {
                RouteNode curr = q.poll();
                // visited.add(curr);
                Node currNode = Node.getNode(curr);
                IntentCode c = currNode.getIntentCode();
                ClockRegion currCR = currNode.getTile().getClockRegion();
                if (currCR != null && cr.getRow() == currCR.getRow() && c == IntentCode.NODE_GLOBAL_VDISTR) {
                    // Only consider base wires
                    if (getNodeStatus.apply(currNode) == NodeStatus.INUSE) {
                        startingPoints.add(curr);
                    } else {
                        // List<PIP> pips = curr.getPIPsBackToSource();
                        List<PIP> pips = curr.getPIPsBackToSourceByNodes();
                        allPIPs.addAll(pips);
                        for (PIP p : pips) {
                            startingPoints.add(p.getStartRouteNode());
                            startingPoints.add(p.getEndRouteNode());
                        }
                    }
                    RouteNode currBase = new RouteNode(currNode);
                    currBase.setParent(null);
                    crToVdist.put(cr, currBase);
                    continue nextClockRegion;
                }
                // for (Wire w : curr.getWireConnections()) {
                //     // if (w.getIntentCode() != IntentCode.NODE_GLOBAL_VDISTR) continue;
                //     // if (!allowedIntentCodes.contains(w.getIntentCode())) {
                //     //     continue;
                //     // }
                //     if (w.getTile().getTileTypeEnum() == TileTypeEnum.INVALID_1_10 || !allowedIntentCodes.contains(w.getIntentCode())) {
                //         continue;
                //     }
                //     Node n = Node.getNode(w);
                //     if (visited.contains(n)) continue;
                //     RouteNode rn = new RouteNode(n.getTile(), n.getWireIndex(), curr, curr.getLevel()+1);
                //     rn.setCost(w.getTile().getManhattanDistance(crTarget));
                //     q.add(rn);
                //     visited.add(n);
                // }
                // Node currNode = Node.getNode(curr);
                for (Node downhill : currNode.getAllDownhillNodes()) {
                    // if (w.getIntentCode() != IntentCode.NODE_GLOBAL_VDISTR) continue;
                    if (!allowedIntentCodes.contains(downhill.getIntentCode())) {
                        continue;
                    }
                    if (visited.contains(downhill)) continue;
                    RouteNode rn = new RouteNode(downhill.getTile(), downhill.getWireIndex(), curr, curr.getLevel()+1);
                    rn.setCost(downhill.getTile().getManhattanDistance(crTarget));
                    q.add(rn);
                    visited.add(downhill);
                }
            }
            throw new RuntimeException("ERROR: Couldn't route to distribution line in clock region " + cr);
        }
        clk.getPIPs().addAll(allPIPs);
        vroute.setParent(null);
        return crToVdist;
    }

    /**
     * Routes from a vertical distribution centroid to destination horizontal distribution lines
     * in the clock regions provided.
     * @param clk The current clock net
     * @param crMap A map that provides a RouteNode reference for each ClockRegion
     * @return The List of nodes from the centroid to the horizontal distribution line.
     */
    public static Map<ClockRegion, RouteNode> routeVerticalToHorizontalDistributionLines(Net clk,
                                                                             Map<ClockRegion, RouteNode> vertDistLines,
                                                                             Collection<ClockRegion> clockRegions,
                                                                             Function<Node, NodeStatus> getNodeStatus) {
        // List<RouteNode> distLines = new ArrayList<>();
        Map<ClockRegion, RouteNode> distLines = new HashMap<>();
        Queue<RouteNode> q = new LinkedList<>();
        Set<PIP> allPIPs = new HashSet<>();
        Set<Node> visited = new HashSet<>();
        Set<IntentCode> allowedIntentCodes = EnumSet.of(
            IntentCode.NODE_GLOBAL_HDISTR,
            IntentCode.NODE_GLOBAL_VDISTR,
            IntentCode.NODE_PINFEED,
            IntentCode.NODE_GLOBAL_HDISTR_LOCAL,
            IntentCode.NODE_GLOBAL_GCLK
        );
        // nextClockRegion: for (Entry<ClockRegion,RouteNode> e : crMap.entrySet()) {
        nextClockRegion: for (ClockRegion targetCR : clockRegions) {
            q.clear();
            RouteNode vertDistLine = vertDistLines.get(targetCR);
            // assert(vertDistLine.getParent() == null);
            vertDistLine.setParent(null);
            q.add(vertDistLine);
            visited.clear();
            visited.add(Node.getNode(vertDistLine));
            
            // ClockRegion targetCR = e.getKey();
            while (!q.isEmpty()) {
                RouteNode curr = q.poll();
                IntentCode c = curr.getIntentCode();
                Node currNode = Node.getNode(curr);
                RouteNode parent = curr.getParent();
                if (targetCR.equals(curr.getTile().getClockRegion()) && 
                    c == IntentCode.NODE_GLOBAL_GCLK &&
                    parent.getIntentCode() == IntentCode.NODE_GLOBAL_HDISTR_LOCAL) {
                    // List<PIP> pips = parent.getPIPsBackToSource();
                    List<PIP> pips = parent.getPIPsBackToSourceByNodes();
                    for (PIP pip : pips) {
                        allPIPs.add(pip);
                        NodeStatus status = getNodeStatus.apply(pip.getStartNode());
                        if (status == NodeStatus.INUSE) {
                            break;
                        }
                        assert(status == NodeStatus.AVAILABLE);
                    }

                    parent.setParent(null);
                    distLines.put(targetCR, parent);
                    // List<PIP> pips = curr.getPIPsBackToSource();
                    // for (PIP pip : pips) {
                    //     allPIPs.add(pip);
                    //     NodeStatus status = getNodeStatus.apply(pip.getStartNode());
                    //     if (status == NodeStatus.INUSE) {
                    //         break;
                    //     }
                    //     assert(status == NodeStatus.AVAILABLE);
                    // }

                    // curr.setParent(null);
                    // distLines.add(curr);
                    continue nextClockRegion;
                }
                // for (Wire w : curr.getWireConnections()) {
                //     // if (!w.getIntentCode().isVersalClocking()) continue;
                //     if (!allowedIntentCodes.contains(w.getIntentCode())) continue;
                //     Node n = Node.getNode(w);
                //     if (visited.contains(n)) continue;
                //     visited.add(n);
                //     q.add(new RouteNode(n.getTile(), n.getWireIndex(), curr, curr.getLevel()+1));
                // }

                for (Node downhill: currNode.getAllDownhillNodes()) {
                    if (!allowedIntentCodes.contains(downhill.getIntentCode())) continue;
                    if (visited.contains(downhill)) continue;
                    visited.add(downhill);
                    q.add(new RouteNode(downhill.getTile(), downhill.getWireIndex(), curr, curr.getLevel()+1));
                }
            }
            throw new RuntimeException("ERROR: Couldn't route to distribution line in clock region " + targetCR);
        }
        clk.getPIPs().addAll(allPIPs);
        return distLines;
    }

    /**
     * Routes from distribution lines to the leaf clock buffers (LCBs)
     * @param clk The current clock net
     * @param lcbTargets The target LCB nodes to route the clock
     */
    public static void routeDistributionToLCBs(Net clk, Map<ClockRegion, RouteNode> distLines, Set<RouteNode> lcbTargets) {
        Map<ClockRegion, Set<RouteNode>> startingPoints = getStartingPoints(distLines);
        routeToLCBs(clk, startingPoints, lcbTargets);
    }

    public static Map<ClockRegion, Set<RouteNode>> getStartingPoints(Map<ClockRegion, RouteNode> distLines) {
        Map<ClockRegion, Set<RouteNode>> startingPoints = new HashMap<>();
        // for (RouteNode rn : distLines) {
        //     ClockRegion cr = rn.getTile().getClockRegion();
        //     startingPoints.computeIfAbsent(cr, k -> new HashSet<>())
        //             .add(rn);
        // }
        for (ClockRegion cr : distLines.keySet()) {
            // ClockRegion cr = rn.getTile().getClockRegion();
            startingPoints.computeIfAbsent(cr, k -> new HashSet<>()).add(distLines.get(cr));
        }
        return startingPoints;
    }

    public static void routeToLCBs(Net clk, Map<ClockRegion, Set<RouteNode>> startingPoints, Set<RouteNode> lcbTargets) {
        Queue<RouteNode> q = RouteNode.createPriorityQueue();
        Set<PIP> allPIPs = new HashSet<>();
        HashSet<RouteNode> visited = new HashSet<>();
        Set<IntentCode> allowedIntentCodes = EnumSet.of(
            IntentCode.NODE_PINFEED,
            IntentCode.NODE_GLOBAL_LEAF,
            IntentCode.NODE_GLOBAL_GCLK
        );

        nextLCB: for (RouteNode lcb : lcbTargets) {
            q.clear();
            visited.clear();
            ClockRegion currCR = lcb.getTile().getClockRegion();
            Set<RouteNode> starts = startingPoints.getOrDefault(currCR, Collections.emptySet());
            for (RouteNode rn : starts) {
                assert(rn.getParent() == null);
            }
            q.addAll(starts);
            while (!q.isEmpty()) {
                RouteNode curr = q.poll();
                visited.add(curr);
                if (lcb.equals(curr)) {
                    List<PIP> pips = curr.getPIPsBackToSource();
                    allPIPs.addAll(pips);

                    Set<RouteNode> s = startingPoints.get(currCR);
                    for (PIP p : pips) {
                        s.add(new RouteNode(p.getTile(),p.getStartWireIndex()));
                        s.add(new RouteNode(p.getTile(),p.getEndWireIndex()));
                    }
                    continue nextLCB;
                }
                // for (Wire w : curr.getWireConnections()) {
                //     // Stay in this clock region
                //     if (!currCR.equals(w.getTile().getClockRegion())) continue;
                //     if (!w.getIntentCode().isVersalClocking()) {
                //         // Final node will not be clocking intent code
                //         SitePin p = w.getSitePin();
                //         if (p == null) continue;
                //         if (p.getSite().getSiteTypeEnum() != SiteTypeEnum.BUFDIV_LEAF) continue;
                //     }
                //     RouteNode rn = new RouteNode(w.getTile(), w.getWireIndex(), curr, curr.getLevel()+1);
                //     if (visited.contains(rn)) continue;
                //     if (rn.getWireName().endsWith("_CLK_CASC_OUT")) continue;
                //     rn.setCost(rn.getManhattanDistance(lcb));
                //     q.add(rn);
                // }
                Node currNode = Node.getNode(curr);
                for (Node downhill : currNode.getAllDownhillNodes()) {
                    // Stay in this clock region
                    if (!currCR.equals(downhill.getTile().getClockRegion())) continue;
                    // if (!w.getIntentCode().isVersalClocking()) {
                    //     // Final node will not be clocking intent code
                    //     SitePin p = w.getSitePin();
                    //     if (p == null) continue;
                    //     if (p.getSite().getSiteTypeEnum() != SiteTypeEnum.BUFDIV_LEAF) continue;
                    // }
                    if (!allowedIntentCodes.contains(downhill.getIntentCode())) continue;
                    RouteNode rn = new RouteNode(downhill.getTile(), downhill.getWireIndex(), curr, curr.getLevel()+1);
                    if (visited.contains(rn)) continue;
                    if (rn.getWireName().endsWith("_I_CASC_PIN")) continue;
                    if (rn.getWireName().endsWith("_CLR_B_PIN")) continue;
                    rn.setCost(rn.getManhattanDistance(lcb));
                    q.add(rn);
                }
            }
            throw new RuntimeException("ERROR: Couldn't route to distribution line in clock region " + lcb);
        }
        clk.getPIPs().addAll(allPIPs);
    }

    /**
     * @param clk
     * @param lcbMappings
     * @param getNodeStatus Lambda for indicating the status of a Node: available, in-use (preserved
     *                      for same net as we're routing), or unavailable (preserved for other net).
     */
    public static void routeLCBsToSinks(Net clk, Map<RouteNode, List<SitePinInst>> lcbMappings,
                                        Function<Node, NodeStatus> getNodeStatus) {
        Set<Node> used = new HashSet<>();
        Set<Node> visited = new HashSet<>();
        Queue<RouteNode> q = new LinkedList<>();
        
        Predicate<Node> isNodeUnavailable = (node) -> getNodeStatus.apply(node) == NodeStatus.UNAVAILABLE;
        Set<IntentCode> allowedIntentCodes = EnumSet.of(
            IntentCode.NODE_CLE_CNODE,
            IntentCode.NODE_INTF_CNODE,
            IntentCode.NODE_CLE_CTRL,
            IntentCode.NODE_INTF_CTRL,
            IntentCode.NODE_IRI,
            IntentCode.NODE_INODE,
            IntentCode.NODE_PINBOUNCE,
            IntentCode.NODE_CLE_BNODE,
            IntentCode.NODE_IMUX,
            IntentCode.NODE_PINFEED
        );

        RouteThruHelper routeThruHelper = new RouteThruHelper(clk.getDesign().getDevice());

        for (Entry<RouteNode,List<SitePinInst>> e : lcbMappings.entrySet()) {
            Set<PIP> currPIPs = new HashSet<>();
            RouteNode lcb = e.getKey();
            assert(lcb.getParent() == null);

            nextPin: for (SitePinInst sink : e.getValue()) {
                RouteNode target = sink.getRouteNode();
                Node targetNode = Node.getNode(target);
                q.clear();
                q.add(lcb);

                while (!q.isEmpty()) {
                    RouteNode curr = q.poll();
                    Node currNode = Node.getNode(curr);
                    if (targetNode.equals(currNode)) {
                        boolean inuse = false;
                        // for (PIP pip : curr.getPIPsBackToSource()) {
                        for (PIP pip : curr.getPIPsBackToSourceByNodes()) {
                            if (inuse) {
                                assert(getNodeStatus.apply(pip.getStartNode()) == NodeStatus.INUSE);
                                continue;
                            }
                            currPIPs.add(pip);
                            NodeStatus status = getNodeStatus.apply(pip.getStartNode());
                            if (status == NodeStatus.INUSE) {
                                // break;
                                inuse = true;
                                continue;
                            }
                            assert(status == NodeStatus.AVAILABLE);
                        }
                        sink.setRouted(true);
                        visited.clear();
                        continue nextPin;
                    }
                    // for (Wire w : curr.getWireConnections()) {
                    //     if (!visited.add(w)) continue;
                    //     if (used.contains(w)) continue;
                    //     if (w.isRouteThru()) continue;
                    //     if (isNodeUnavailable.test(w.getNode())) continue;
                    //     q.add(new RouteNode(w.getTile(), w.getWireIndex(), curr, curr.getLevel()+1));
                    // }
                    for (Node downhill : currNode.getAllDownhillNodes()) {
                        if (!allowedIntentCodes.contains(downhill.getIntentCode())) continue;
                        if (!visited.add(downhill)) continue;
                        if (used.contains(downhill)) continue;
                        // if (w.isRouteThru()) continue;
                        if (routeThruHelper.isRouteThru(currNode, downhill) && downhill.getIntentCode() != IntentCode.NODE_IRI) continue;
                        if (isNodeUnavailable.test(downhill)) continue;
                        q.add(new RouteNode(downhill.getTile(), downhill.getWireIndex(), curr, curr.getLevel()+1));
                    }
                }
                throw new RuntimeException("ERROR: Couldn't route LCB " + e.getKey() + " to Pin " + sink);
            }

            List<PIP> clkPIPs = clk.getPIPs();
            for (PIP p : currPIPs) {
                used.add(p.getStartNode());
                used.add(p.getEndNode());
                clkPIPs.add(p);
            }
        }
    }

    /**
     * Routes from a GLOBAL_VERTICAL_ROUTE to horizontal distribution lines.
     * @param clk The clock net to be routed.
     * @param vroute The node to start the route.
     * @param clockRegions Target clock regions.
     * @param down To indicate if it is routing to the group of top clock regions.
     * @return A list of RouteNodes indicating the reached horizontal distribution lines.
     */
    public static Map<ClockRegion, RouteNode> routeToHorizontalDistributionLines(Net clk,
                                                                     RouteNode vroute,
                                                                     Collection<ClockRegion> clockRegions,
                                                                     boolean down,
                                                                     Function<Node, NodeStatus> getNodeStatus) {
        // RouteNode centroidDistNode = VersalClockRouting.transitionCentroidToVerticalDistributionLine(clk, vroute, down);
        // System.out.println("centroidDistNode: " + centroidDistNode + " " + centroidDistNode.getTile().getClockRegion());
        // if (centroidDistNode == null) return null;

        Map<ClockRegion, RouteNode> vertDistLines = routeVrouteToVerticalDistributionLines(clk, vroute, clockRegions, getNodeStatus);

        // ClockRegion centroidClockRegion = vroute.getTile().getClockRegion();
        // Device device = centroidClockRegion.getDevice();
        // // Y -> vertical distribution line
        // Map<ClockRegion, RouteNode> vertDistLines = new HashMap<>();
        // // Y -> clock regions in the same column
        // // Map<Integer, ClockRegion> clockRegionsOfVertDistLines = new HashMap<>();
        // for (ClockRegion cr: clockRegions) {
        //     RouteNode vDistLine = null;
        //     for (ClockRegion keyCr: vertDistLines.keySet()) {
        //         if (cr.getRow() == keyCr.getRow()) {
        //             vDistLine = vertDistLines.get(keyCr);
        //             break;
        //         }
        //     }
        //     if (vDistLine == null) {
        //         // TODO: 
        //         vDistLine = transitionCentroidToDistributionLine(clk, vroute, vDistLineClockRegion);
        //     }
        //     assert(vDistLine != null);
        //     vertDistLines.put(cr, vDistLine);
        // }

        // for (ClockRegion cr: vertDistLines.keySet()) {
        //     RouteNode vDistNode = vertDistLines.get(cr);
        //     System.out.println(cr + " " + vDistNode + " " + vDistNode.getTile().getClockRegion());
        // }

        // check(clk, new ArrayList<RouteNode>(vertDistLines.values()));

        // System.out.println(clockRegions);

        // TODO: 
        Map<ClockRegion, RouteNode> horiDistLines = routeVerticalToHorizontalDistributionLines(clk, vertDistLines, clockRegions, getNodeStatus);
        return horiDistLines;
    }

    /**
     * Routes a partially routed clock.
     * It will examine the clock net for SitePinInsts and assumes any present are already routed. It
     * then invokes {@link DesignTools#createMissingSitePinInsts(Design, Net)} to discover those not
     * yet routed.
     * @param design  The current design
     * @param clkNet The partially routed clock net to make fully routed
     * @param getNodeStatus Lambda for indicating the status of a Node: available, in-use (preserved
     *                      for same net as we're routing), or unavailable (preserved for other net).
     */
    public static void incrementalClockRouter(Design design,
                                              Net clkNet,
                                              Function<Node,NodeStatus> getNodeStatus) {
        // Assume all existing site pins are already routed
        Set<SitePinInst> existingPins = new HashSet<>(clkNet.getSinkPins());

        // Find any missing site pins, to be used as target, routable sinks
        DesignTools.createMissingSitePinInsts(design, clkNet);

        List<SitePinInst> createdPins = new ArrayList<>(clkNet.getSinkPins());
        createdPins.removeAll(existingPins);

        if (createdPins.isEmpty())
            return;

        incrementalClockRouter(clkNet, createdPins, getNodeStatus);
    }

    /**
     * Routes a list of unrouted pins from a partially routed clock.
     * @param clkNet The partially routed clock net to make fully routed
     * @param clkPins A list of unrouted pins on the clock net to route
     * @param getNodeStatus Lambda for indicating the status of a Node: available, in-use (preserved
     *                      for same net as we're routing), or unavailable (preserved for other net).
     */
    public static void incrementalClockRouter(Net clkNet,
                                              List<SitePinInst> clkPins,
                                              Function<Node,NodeStatus> getNodeStatus) {
        // Find all horizontal distribution lines to be used as starting points and create a map
        // lookup by clock region
        Map<ClockRegion,Set<RouteNode>> startingPoints = new HashMap<>();
        Set<Node> vroutesUp = new HashSet<>();
        Set<Node> vroutesDown = new HashSet<>();
        int centroidY = -1;
        for (PIP p : clkNet.getPIPs()) {
            Node startNode = p.getStartNode();
            Node endNode = p.getEndNode();
            for (Node node : new Node[] {startNode, endNode}) {
                if (node == null) continue;
                IntentCode ic = node.getIntentCode();
                if (ic == IntentCode.NODE_GLOBAL_HDISTR) {
                    for (Wire w : node.getAllWiresInNode()) {
                        RouteNode rn = new RouteNode(w.getTile(), w.getWireIndex());
                        ClockRegion cr = w.getTile().getClockRegion();
                        if (cr != null) {
                            assert(rn.getParent() == null);
                            startingPoints.computeIfAbsent(cr, n -> new HashSet<>())
                                    .add(rn);
                        }
                    }
                } else if (node == startNode && endNode.getIntentCode() == IntentCode.NODE_GLOBAL_VDISTR) {
                    if (ic == IntentCode.NODE_GLOBAL_VROUTE || ic == IntentCode.NODE_GLOBAL_HROUTE) {
                        // Centroid lays where {HROUTE, VROUTE} -> VDISTR
                        assert(centroidY == -1);
                        centroidY = p.getTile().getTileYCoordinate();
                    } else {
                        Tile startTile = startNode.getTile();
                        Tile endTile = endNode.getTile();
                        if (endTile == startTile) {
                            for (Wire w : endNode.getAllWiresInNode()) {
                                if (w.getTile() != endTile) {
                                    endTile = w.getTile();
                                    break;
                                }
                            }
                        }

                        int startTileY = startTile.getTileYCoordinate();
                        int endTileY = endTile.getTileYCoordinate();
                        if (endTileY > startTileY) {
                            vroutesUp.add(endNode);
                        } else if (endTileY < startTileY) {
                            vroutesDown.add(endNode);
                        }
                    }
                }
            }
        }
        assert(centroidY != -1);

        Node currNode = null;
        int currDelta = Integer.MAX_VALUE;
        for (Node node : vroutesUp) {
            int delta = node.getTile().getTileYCoordinate() - centroidY;
            assert(delta >= 0);
            if (delta < currDelta) {
                currDelta = delta;
                currNode = node;
            }
        }
        RouteNode vrouteUp = currNode != null ? new RouteNode(currNode.getTile(), currNode.getWireIndex()) : null;

        currNode = null;
        currDelta = Integer.MAX_VALUE;
        for (Node node : vroutesDown) {
            int delta = centroidY - node.getTile().getTileYCoordinate();
            assert(delta >= 0);
            if (delta < currDelta) {
                currDelta = delta;
                currNode = node;
            }
        }
        RouteNode vrouteDown = currNode != null ? new RouteNode(currNode.getTile(), currNode.getWireIndex()) : null;

        // Find the target leaf clock buffers (LCBs), route from horizontal dist lines to those
        Map<RouteNode, List<SitePinInst>> lcbMappings = GlobalSignalRouting.getLCBPinMappings(clkPins, getNodeStatus);

        final int finalCentroidY = centroidY;
        Set<ClockRegion> newUpClockRegions = new HashSet<>();
        Set<ClockRegion> newDownClockRegions = new HashSet<>();
        for (Map.Entry<RouteNode, List<SitePinInst>> e : lcbMappings.entrySet()) {
            RouteNode lcb = e.getKey();
            ClockRegion currCR = lcb.getTile().getClockRegion();
            startingPoints.computeIfAbsent(currCR, n -> {
                if (currCR.getUpperLeft().getTileYCoordinate() > finalCentroidY) {
                    newUpClockRegions.add(currCR);
                } else {
                    newDownClockRegions.add(currCR);
                }
                return new HashSet<>();
            });
        }
        if (!newUpClockRegions.isEmpty()) {
            List<RouteNode> upLines = UltraScaleClockRouting.routeToHorizontalDistributionLines(clkNet,
                    vrouteUp,
                    newUpClockRegions,
                    false,
                    getNodeStatus);
            if (upLines != null) {
                for (RouteNode rnode : upLines) {
                    rnode.setParent(null);
                    startingPoints.get(rnode.getTile().getClockRegion()).add(rnode);
                }
            }
        }
        if (!newDownClockRegions.isEmpty()) {
            List<RouteNode> downLines = UltraScaleClockRouting.routeToHorizontalDistributionLines(clkNet,
                    vrouteDown,
                    newDownClockRegions,
                    true,
                    getNodeStatus);
            if (downLines != null) {
                for (RouteNode rnode : downLines) {
                    rnode.setParent(null);
                    startingPoints.get(rnode.getTile().getClockRegion()).add(rnode);
                }
            }
        }

        UltraScaleClockRouting.routeToLCBs(clkNet, startingPoints, lcbMappings.keySet());

        // Last mile routing from LCBs to SLICEs
        UltraScaleClockRouting.routeLCBsToSinks(clkNet, lcbMappings, getNodeStatus);

        // Remove duplicates
        Set<PIP> uniquePIPs = new HashSet<>(clkNet.getPIPs());
        clkNet.setPIPs(uniquePIPs);
    }

    
    // debug
    private static void check(Net clk, List<RouteNode> rnodes) {
        Node sourceNode = clk.getSource().getConnectedNode();
        Map<Node, Node> reversedEdges = new HashMap<>();
        Set<PIP> pips = new HashSet<>(clk.getPIPs());
        for (PIP pip: pips) {
            if (pip.isBidirectional() && pip.isReversed()) {
                assert(!reversedEdges.containsKey(pip.getStartNode()));
                reversedEdges.put(pip.getStartNode(), pip.getEndNode());
            } else {
                assert(!reversedEdges.containsKey(pip.getEndNode()));
                reversedEdges.put(pip.getEndNode(), pip.getStartNode());
            }
        }
        for (RouteNode rnode: rnodes) {
            Node node = Node.getNode(rnode);
            Node curr = node;
            while (curr != null && !curr.equals(sourceNode)) {
                curr = reversedEdges.get(curr);
            }
            if (curr == null) {
                System.out.println("Node " + node + ": antenna");
            } else {
                System.out.println("Node " + node + ": connected");
            }
        }
    }
}
