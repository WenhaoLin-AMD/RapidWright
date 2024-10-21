/*
 * Copyright (c) 2022-2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Eddie Hung, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

import com.xilinx.rapidwright.router.RouteNode;
import com.xilinx.rapidwright.util.Utils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

public class TestNode {
    @ParameterizedTest
    @CsvSource({
            "xcvu19p-fsva3824-1-e,INT_X182Y338/WW12_BEG5",
            "xcvu19p-fsva3824-1-e,INT_X182Y338/NN12_BEG5",
    })
    public void testGetDownhillUphillNodesUnique(String deviceName, String nodeName) {
        Device device = Device.getDevice(deviceName);
        Node node = device.getNode(nodeName);

        List<Node> downhill = node.getAllDownhillNodes();
        if (node.getWireName().equals("WW12_BEG5")) {
            Assertions.assertNotEquals(downhill.size(), new HashSet<>(downhill).size());
        } else {
            Assertions.assertEquals(downhill.size(), new HashSet<>(downhill).size());
        }

        Collection<Node> downhillUnique = node.getAllDownhillNodes(new HashSet<>());
        Assertions.assertEquals(downhillUnique.size(), new HashSet<>(downhillUnique).size());

        List<Node> uphill = node.getAllUphillNodes();
        Assertions.assertEquals(uphill.size(), new HashSet<>(uphill).size());
        
        Collection<Node> uphillUnique = node.getAllUphillNodes(new HashSet<>());
        Assertions.assertEquals(uphillUnique.size(), new HashSet<>(uphillUnique).size());
    }
    
    @Test
    public void testNullNode() {
        Device d = Device.getDevice("xcvm1802-vfvc1760-1LHP-i-L");
        PIP p = d.getPIP("BLI_LS_CORE_X90Y335/BLI_LS_CORE_R180.HSR_GRP1_A_BLI_LOGIC_OUTS0->>BLI_GRP1_A_BLI_LOGIC_OUTS0");
        Assertions.assertNull(p.getStartNode());
    }

    @Test
    public void testUphillNodeIsInvalid() {
        // link_design -part [lindex [get_parts xcvu440*] 0]
        // get_nodes -uphill -of [get_nodes INT_INT_INTERFACE_XIPHY_FT_X157Y688/LOGIC_OUTS_R0]
        // WARNING: [Vivado 12-2683] No nodes matched 'get_nodes -uphill -of [get_nodes INT_INT_INTERFACE_XIPHY_FT_X157Y688/LOGIC_OUTS_R0]'
        Device d = Device.getDevice("xcvu440");
        Node n = d.getNode("INT_INT_INTERFACE_XIPHY_FT_X157Y688/LOGIC_OUTS_R0");
        Assertions.assertNotNull(n);
        Assertions.assertTrue(n.getAllUphillNodes().isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
            // UltraScale+ part
            "xcvu3p,INT_X37Y220,BOUNCE_",
            "xcvu3p,INT_X37Y220,BYPASS_",
            "xcvu3p,INT_X37Y220,INT_NODE_GLOBAL_",
            "xcvu3p,INT_X37Y220,INT_NODE_IMUX_",
            "xcvu3p,INT_X37Y220,INODE_",
            // These nodes fanout to NESW nodes in the tile above or below
            // "xcvu3p,INT_X37Y220,SDQNODE_",

            // UltraScale part
            "xcvu065,INT_X38Y220,BOUNCE_",
            "xcvu065,INT_X38Y220,BYPASS_",
            "xcvu065,INT_X38Y220,INT_NODE_GLOBAL_",
            "xcvu065,INT_X38Y220,INT_NODE_IMUX_",
            "xcvu065,INT_X38Y220,INODE_",
            // "xcvu065,INT_X38Y220,QLND",
            // "xcvu065,INT_X38Y220,SDND",
    })
    public void testNodeReachabilityUltraScale(String partName, String tileName, String wirePrefix) {
        Device device = Device.getDevice(partName);
        Tile baseTile = device.getTile(tileName);
        Queue<Node> queue = new ArrayDeque<>();
        for (String wireName : baseTile.getWireNames()) {
            if (!wireName.startsWith(wirePrefix)) {
                continue;
            }
            queue.add(Node.getNode(baseTile, wireName));
        }
        System.out.println("Initial queue.size() = " + queue.size());

        // Print out the prefixes of nodes that are immediately uphill of these wire prefixes
        // (i.e. "BOUNCE_E_0_FT1" -> "BOUNCE_")
        System.out.println("Immediately uphill:");
        queue.stream().map(Node::getAllUphillNodes).flatMap(List::stream).map(Node::getWireName)
                .map(s -> s.replaceFirst("((BOUNCE|BYPASS|CTRL|INT_NODE_[^_]+|INODE)_).*", "$1"))
                .map(s -> s.replaceFirst(
                                                   // UltraScale+
                        (partName.endsWith("p")) ? "((CLE_CLE_[LM]_SITE_0|CLK_LEAF_SITES|EE[124]|INT_INT_SDQ|NN[12]|SS[12]|WW[124])_).*"
                                                   // UltraScale
                                                 : "((CLE_CLE_[LM]_SITE_0|CLK_BUFCE_LEAF_X16_1_CLK|EE[124]|INT_INT_SINGLE|NN[12]|SS[12]|WW[124])_).*",
                        "$1"))
                .distinct()
                .sorted()
                .forEachOrdered(s -> System.out.println("\t" + s));

        // Print out the prefixes of nodes that are immediately downhill of these wire prefixes
        System.out.println("Immediately downhill:");
        queue.stream().map(Node::getAllDownhillNodes).flatMap(List::stream).map(Node::getWireName)
                .map(s -> s.replaceFirst("((BOUNCE|BYPASS|CTRL|IMUX|INT_NODE_[^_]+|INODE)_).*", "$1"))
                .distinct()
                .sorted()
                .forEachOrdered(s -> System.out.println("\t" + s));

        Set<Node> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            Node node = queue.poll();
            for (Node downhill : node.getAllDownhillNodes()) {
                if (!visited.add(downhill)) {
                    continue;
                }
                if (!Utils.isInterConnect(downhill.getTile().getTileTypeEnum())) {
                    continue;
                }
                Assertions.assertEquals(baseTile.getTileXCoordinate(),
                        downhill.getTile().getTileXCoordinate());
                queue.add(downhill);
            }
        }
        System.out.println("visited.size() = " + visited.size());
    }

    @Test
    public void testSDQNodeReachabilityVersal() {
        String partName = "xcvp1002";
        String tileName = "INT_X26Y145";
        System.out.println("----------------------------------------" );
        System.out.println("Testing: " + partName + " " + tileName);
        Device device = Device.getDevice(partName);
        Tile baseTile = device.getTile(tileName);
        Queue<Node> queue = new ArrayDeque<>();

        for (String wireName : baseTile.getWireNames()) {
            Node node = Node.getNode(baseTile, wireName);
            if (node.getIntentCode() != IntentCode.NODE_SDQNODE || !wireName.startsWith("OUT")) {
                continue;
            }
            queue.add(node);
        }
        System.out.println("OUT* queue.size() = " + queue.size());

        System.out.println("Immediately uphill:");
        queue.stream().map(Node::getAllUphillNodes).flatMap(List::stream).map(Node::getIntentCode)
                .distinct()
                .sorted()
                .forEachOrdered(s -> System.out.println("\t" + s));

        System.out.println("Immediately downhill:");
        queue.stream().map(Node::getAllDownhillNodes).flatMap(List::stream).map(Node::getIntentCode)
                .distinct()
                .sorted()
                .forEachOrdered(s -> System.out.println("\t" + s));

        queue.clear();
        for (String wireName : baseTile.getWireNames()) {
            Node node = Node.getNode(baseTile, wireName);
            if (node.getIntentCode() != IntentCode.NODE_SDQNODE || !wireName.startsWith("INT")) {
                continue;
            }
            queue.add(node);
        }
        System.out.println("INT* queue.size() = " + queue.size());

        System.out.println("Immediately uphill:");
        queue.stream().map(Node::getAllUphillNodes).flatMap(List::stream).map(Node::getIntentCode)
                .distinct()
                .sorted()
                .forEachOrdered(s -> System.out.println("\t" + s));

        System.out.println("Immediately downhill:");
        queue.stream().map(Node::getAllDownhillNodes).flatMap(List::stream).map(Node::getIntentCode)
                .distinct()
                .sorted()
                .forEachOrdered(s -> System.out.println("\t" + s));
    }

    @ParameterizedTest
    @MethodSource
    public void testLUTNodeReachabilityVersal(String partName, String tileName, IntentCode ic) {
        System.out.println("----------------------------------------" );
        System.out.println("Testing: " + partName + " " + tileName + " " + ic);
        Device device = Device.getDevice(partName);
        Tile baseTile = device.getTile(tileName);
        Queue<Node> queue = new ArrayDeque<>();
        for (String wireName : baseTile.getWireNames()) {
            Node node = Node.getNode(baseTile, wireName);
            if (node.getIntentCode() != ic) {
                continue;
            }
            queue.add(node);
        }
        System.out.println("Initial queue.size() = " + queue.size());
        
        // for (String wireName : baseTile.getWireNames()) {
            // if (!wireName.matches("\\w*[01]_[A-H][1-5]_PIN")) {
            //     continue;
            // }
        //     queue.add(Node.getNode(baseTile, wireName));
        // }
        // System.out.println("Initial queue.size() = " + queue.size());

        System.out.println("Immediately uphill:");
        queue.stream().map(Node::getAllUphillNodes).flatMap(List::stream).map(Node::getIntentCode)
                .distinct()
                .sorted()
                .forEachOrdered(s -> System.out.println("\t" + s));

        System.out.println("2-step uphill:");
        queue.stream().map(Node::getAllUphillNodes).flatMap(List::stream).map(Node::getAllUphillNodes).flatMap(List::stream).map(Node::getIntentCode)
                .distinct()
                .sorted()
                .forEachOrdered(s -> System.out.println("\t" + s));

        System.out.println("3-step uphill:");
        queue.stream().map(Node::getAllUphillNodes).flatMap(List::stream).map(Node::getAllUphillNodes).flatMap(List::stream).map(Node::getAllUphillNodes).flatMap(List::stream).map(Node::getIntentCode)
                .distinct()
                .sorted()
                .forEachOrdered(s -> System.out.println("\t" + s));

        System.out.println("Immediately downhill:");
        queue.stream().map(Node::getAllDownhillNodes).flatMap(List::stream).map(Node::getIntentCode)
                .distinct()
                .sorted()
                .forEachOrdered(s -> System.out.println("\t" + s));

        System.out.println("2-step downhill:");
        queue.stream().map(Node::getAllDownhillNodes).flatMap(List::stream).map(Node::getAllDownhillNodes).flatMap(List::stream).map(Node::getIntentCode)
                .distinct()
                .sorted()
                .forEachOrdered(s -> System.out.println("\t" + s));

        System.out.println("3-step downhill:");
        queue.stream().map(Node::getAllDownhillNodes).flatMap(List::stream).map(Node::getAllDownhillNodes).flatMap(List::stream).map(Node::getAllDownhillNodes).flatMap(List::stream).map(Node::getIntentCode)
                .distinct()
                .sorted()
                .forEachOrdered(s -> System.out.println("\t" + s));
    }

    static List<Arguments> testLUTNodeReachabilityVersal() {
        return List.of(
            Arguments.of("xcvp1002", "CLE_BC_CORE_X10Y222", IntentCode.NODE_CLE_CNODE),
            Arguments.of("xcvp1002", "CLE_BC_CORE_X10Y222", IntentCode.NODE_CLE_CTRL),
            Arguments.of("xcvp1002", "CLE_BC_CORE_X10Y222", IntentCode.NODE_CLE_BNODE),
            Arguments.of("xcvp1002", "CLE_W_CORE_X10Y222", IntentCode.NODE_PINFEED),
            Arguments.of("xcvp1002", "CLE_E_CORE_X11Y222", IntentCode.NODE_CLE_OUTPUT),
            Arguments.of("xcvp1002", "INT_X26Y145", IntentCode.NODE_SDQNODE)
            // Arguments.of("xcvp1002", "CLE_W_CORE_X26Y145", IntentCode.NODE_PINFEED)
            // Arguments.of("xcvp1002", "INT_X26Y145", IntentCode.NODE_CLE_BNODE),
            // Arguments.of("xcvp1002", "INT_X30Y120", IntentCode.NODE_CLE_BNODE),
            // Arguments.of("xcvp1002", "INT_X26Y145", IntentCode.NODE_CLE_CNODE),
            // Arguments.of("xcvp1002", "INT_X30Y120", IntentCode.NODE_CLE_CNODE)
        );
    }

    @Test
    public void testCKENCTRLFedByOnlyCNODEOnVersal() {
        Device device = Device.getDevice("xcvp1002");
        for (int x = 1; x < device.getColumns(); x++) {
            for (int y = 0; y < device.getRows(); y++) {
                Tile tile = device.getTile("CLE_BC_CORE", x, y);
                if (tile == null) {
                    continue;
                }
                for (String wireName: tile.getWireNames()) {
                    if (!wireName.startsWith("CTRL") || wireName.endsWith("B8")) {
                        // B8 connects to pin WE, which is fed by BNODEs and CNODEs
                        continue;
                    }
                    Node node = Node.getNode(tile, wireName);
                    IntentCode ic = node.getIntentCode();
                    Assertions.assertTrue(ic == IntentCode.NODE_CLE_CTRL);
                    for (Node uphill: node.getAllUphillNodes()) {
                        boolean condition = uphill.getIntentCode() == IntentCode.NODE_CLE_CNODE || uphill.getWireName().equals("VCC_WIRE");
                        if (condition == false) {
                            System.out.println(node + " " + uphill.getIntentCode() + " " + uphill.getWireName());
                        }
                        // Assertions.assertTrue(condition);
                    }
                }
            }
        }
    }

    @Test
    public void testCTRLFedByBNODEAndCNODEOnVersal() {
        Device device = Device.getDevice("xcvp1002");
        for (int x = 1; x < device.getColumns(); x++) {
            for (int y = 0; y < device.getRows(); y++) {
                Tile tile = device.getTile("CLE_BC_CORE", x, y);
                if (tile == null) {
                    continue;
                }
                for (String wireName: tile.getWireNames()) {
                    if (!wireName.startsWith("CTRL")) {
                        continue;
                    }
                    Node node = Node.getNode(tile, wireName);
                    IntentCode ic = node.getIntentCode();
                    Assertions.assertTrue(ic == IntentCode.NODE_CLE_CTRL);
                    for (Node uphill: node.getAllUphillNodes()) {
                        boolean condition = uphill.getIntentCode() == IntentCode.NODE_CLE_BNODE || uphill.getIntentCode() == IntentCode.NODE_CLE_CNODE || uphill.getWireName().equals("VCC_WIRE");
                        if (condition == false) {
                            System.out.println(node + " " + uphill.getIntentCode() + " " + uphill.getWireName());
                        }
                        Assertions.assertTrue(condition);
                    }
                }
            }
        }
    }

    // @ParameterizedTest
    // @MethodSource
    @Test
    public void testBOUNCEWNodesOnVersal() {
        Device device = Device.getDevice("xcvp1002");
        for (int x = 1; x < device.getColumns(); x++) {
            for (int y = 0; y < device.getRows(); y++) {
                if (device.getTile("CLE_BC_CORE", x-1, y) == null) {
                    assertNull(device.getTile("CLE_E_CORE", x, y));

                    Tile tile = device.getTile("INTF_HB_LOCF_TL_TILE", x, y);
                    if (tile == null) {
                        tile = device.getTile("INTF_HB_LOCF_BR_TILE", x-1, y);
                    }
                    if (tile == null) {
                        tile = device.getTile("INTF_LOCF_TL_TILE", x, y);
                    }
                    if (tile == null) {
                        tile = device.getTile("INTF_LOCF_BR_TILE", x, y);
                    }
                    if (tile == null) {
                        tile = device.getTile("INTF_LOCF_BL_TILE", x, y);
                    }
                    if (tile == null) {
                        tile = device.getTile("INTF_PSS_BL_TILE", x, y);
                    }
                    if (tile == null) {
                        tile = device.getTile("INTF_PSS_TL_TILE", x, y);
                    }
                    if (tile == null) {
                        tile = device.getTile("INTF_CFRM_TL_TILE", x, y);
                    }
                    if (tile == null) {
                        tile = device.getTile("INTF_CFRM_BL_TILE", x, y);
                    }
                    if (tile != null) {
                        Tile intTile = device.getTile("INT", x, y);
                        for (int i = 0; i < 32; i++) {
                            String wireName = "BOUNCE_W" + i;
                            Node node = Node.getNode(intTile, wireName);
                            List<Node> downhills = node.getAllDownhillNodes();
                            if (downhills.size() != 10) {
                                System.out.println(node);
                                for (Node downhill: downhills) {
                                    System.out.println(downhill + " " + downhill.getIntentCode());
                                }
                            }
                            assertEquals(downhills.size(), 10);
                            for (int j = 0; j < 8; j++) {
                                assertEquals(downhills.get(j).getIntentCode(), IntentCode.NODE_INTF_BNODE);
                            }
                            for (int j = 8; j < 10; j++) {
                                assertEquals(downhills.get(j).getIntentCode(), IntentCode.NODE_INTF_CNODE);
                            }
                        }
                    } else {
                        Tile intTile = device.getTile("INT", x, y);
                        if (intTile == null) {
                            continue;
                        }
                        for (int i = 0; i < 32; i++) {
                            String wireName = "BOUNCE_W" + i;
                            Node node = Node.getNode(intTile, wireName);
                            List<Node> downhills = node.getAllDownhillNodes();
                            if (downhills.size() != 4) {
                                System.out.println(x + " " + y + " " + node);
                                for (Node downhill: downhills) {
                                    System.out.println(downhill + " " + downhill.getIntentCode());
                                }
                            }
                            assertEquals(downhills.size(), 4);
                            for (int j = 0; j < 4; j++) {
                                assertEquals(downhills.get(j).getIntentCode(), IntentCode.NODE_INODE);
                            }
                        }
                    }
                } else {
                    Tile intTile = device.getTile("INT", x, y);
                    if (intTile == null) {
                        continue;
                    }
                    for (int i = 0; i < 32; i++) {
                        String wireName = "BOUNCE_W" + i;
                        Node node = Node.getNode(intTile, wireName);
                        List<Node> downhills = node.getAllDownhillNodes();
                        if (i < 16) {
                            assertEquals(downhills.size(), 5);
                            assertEquals(downhills.get(0).getIntentCode(), IntentCode.NODE_PINFEED);
                            for (int j = 1; j < 5; j++) {
                                assertEquals(downhills.get(j).getIntentCode(), IntentCode.NODE_INODE);
                            }
                        } else {
                            assertEquals(downhills.size(), 11);
                            for (int j = 0; j < 8; j++) {
                                assertEquals(downhills.get(j).getIntentCode(), IntentCode.NODE_CLE_BNODE);
                            }
                            for (int j = 8; j < 10; j++) {
                                assertEquals(downhills.get(j).getIntentCode(), IntentCode.NODE_CLE_CNODE);
                            }
                            assertEquals(downhills.get(10).getIntentCode(), IntentCode.NODE_PINFEED);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void testBOUNCEENodesOnVersal() {
        Device device = Device.getDevice("xcvp1002");
        for (int x = 1; x < device.getColumns(); x++) {
            for (int y = 0; y < device.getRows(); y++) {
                if (device.getTile("CLE_BC_CORE", x, y) == null) {
                    continue;
                }
                
                Tile intTile = device.getTile("INT", x, y);
                if (intTile == null) {
                    continue;
                }
                for (int i = 0; i < 32; i++) {
                    String wireName = "BOUNCE_E" + i;
                    Node node = Node.getNode(intTile, wireName);
                    List<Node> downhills = node.getAllDownhillNodes();
                    if (i < 16) {
                        for (int j = 0; j < 4; j++) {
                            if (downhills.get(j).getIntentCode() != IntentCode.NODE_INODE) {
                                System.out.println(node);
                                for (Node downhill: downhills) {
                                    System.out.println(downhill + " " + downhill.getIntentCode());
                                }
                            }
                            assertEquals(downhills.get(j).getIntentCode(), IntentCode.NODE_INODE);
                        }
                        if (downhills.size() == 5)
                            assertEquals(downhills.get(4).getIntentCode(), IntentCode.NODE_PINFEED);
                    } else {
                        int offset = downhills.size() == 11 ? 1 : 0;
                        // if (downhills.size() != 11) {
                        //     System.out.println(node);
                        //     for (Node downhill: downhills) {
                        //         System.out.println(downhill + " " + downhill.getIntentCode());
                        //     }
                        // }
                        // assertEquals(downhills.size(), 11);
                        if (offset == 1) {
                            assertEquals(downhills.get(0).getIntentCode(), IntentCode.NODE_PINFEED);
                        }
                        for (int j = offset; j < offset + 8; j++) {
                            assertEquals(downhills.get(j).getIntentCode(), IntentCode.NODE_CLE_BNODE);
                        }
                        for (int j = offset + 8; j < offset + 10; j++) {
                            assertEquals(downhills.get(j).getIntentCode(), IntentCode.NODE_CLE_CNODE);
                        }
                        
                    }
                }
            }
        }
    }

    @Test
    public void testCLEBCCORE() {
        Device device = Device.getDevice("xcvp1002");
        // tile name in Vivado: CLE_BC_CORE_1_X14Y0
        Tile bcmxTile = device.getTile("CLE_BC_CORE", 14, 0);
        System.out.println(bcmxTile + " " + bcmxTile.getTileTypeEnum());
        Tile bcTile = device.getTile("CLE_BC_CORE", 14, 192);
        System.out.println(bcTile + " " + bcTile.getTileTypeEnum());
    }

    @ParameterizedTest
    @MethodSource
    public void testBCNodeInINTTileReachAllNeighborLUTInputPinsIn3Steps(String partName, int x, int y) {
        System.out.println("----------------------------------------" );
        System.out.println("Testing if B/CNODEs in INTX" + x + "Y" + y + " can reach all LUT input pins in neighbor tiles");
        Device device = Device.getDevice(partName);
        Tile intTile = device.getTile("INT", x, y);
        Assertions.assertNotNull(intTile);
        Tile wNeighborTile = device.getTile("CLE_W_CORE", x, y);
        // Assertions.assertNotNull(wNeighborTile);
        Tile eNeighborTile = device.getTile("CLE_E_CORE", x, y);
        // Assertions.assertNotNull(eNeighborTile);
        List<Node> allInputNodesOfLUTs = new ArrayList<>();
        for (Tile neighborTile: Arrays.asList(wNeighborTile, eNeighborTile)) {
            if (neighborTile == null) continue;
            System.out.println("Find " + neighborTile.toString());
            for (String wireName: neighborTile.getWireNames()) {
                if (!wireName.matches("\\w*[01]_[A-H][1-5]_PIN")) {
                    continue;
                }
                allInputNodesOfLUTs.add(Node.getNode(neighborTile, wireName));
            }
        }

        List<Node> allNodesReachedByBNodes = new ArrayList<>();
        allNodesReachedByBNodes.addAll(Arrays.asList(intTile.getWireNames()).stream()
            .map(w -> Node.getNode(intTile, w))
            .filter(n -> {return (n.getIntentCode() == IntentCode.NODE_CLE_BNODE);})
            .map(n -> n.getAllDownhillNodes())
            .flatMap(List::stream)
            .map(n -> n.getAllDownhillNodes())
            .flatMap(List::stream)
            .map(n -> n.getAllDownhillNodes())
            .flatMap(List::stream)
            .distinct()
            .collect(Collectors.toList()));

        Assertions.assertTrue(allNodesReachedByBNodes.containsAll(allInputNodesOfLUTs));

        List<Node> allNodesReachedByCNodes = new ArrayList<>();
        allNodesReachedByCNodes.addAll(Arrays.asList(intTile.getWireNames()).stream()
            .map(w -> Node.getNode(intTile, w))
            .filter(n -> {return (n.getIntentCode() == IntentCode.NODE_CLE_CNODE);})
            .map(n -> n.getAllDownhillNodes())
            .flatMap(List::stream)
            .map(n -> n.getAllDownhillNodes())
            .flatMap(List::stream)
            .map(n -> n.getAllDownhillNodes())
            .flatMap(List::stream)
            .distinct()
            .collect(Collectors.toList()));

        Assertions.assertTrue(allNodesReachedByCNodes.containsAll(allInputNodesOfLUTs));
    }

    static List<Arguments> testBCNodeInINTTileReachAllNeighborLUTInputPinsIn3Steps() {
        return Arrays.asList(
            Arguments.of("xcvp1002", 26, 145),
            Arguments.of("xcvp1002", 26, 147),
            Arguments.of("xcvp1002", 30, 147),
            Arguments.of("xcvp1002", 40, 70),
            Arguments.of("xcvp1002", 15, 70)
        );
    }

    @ParameterizedTest
    @CsvSource({
            // https://github.com/Xilinx/RapidWright/issues/983
            "xcvh1782,GTYP_QUAD_SINGLE_X0Y240/GTYP_QUAD_SITE_0_APB3PRDATA_0_"
    })
    public void testWireNodeMismatch(String deviceName, String nodeName) {
        Device device = Device.getDevice(deviceName);
        Node node = device.getNode(nodeName);
        Assertions.assertNotNull(node);

        Wire[] allWiresInNode = node.getAllWiresInNode();
        Assertions.assertNotEquals(0, allWiresInNode.length);
        for (Wire wire : allWiresInNode) {
            Assertions.assertEquals(node, wire.getNode());
        }
    }

    @ParameterizedTest
    @CsvSource({
            "xcvu3p,INT_X0Y0/BYPASS_W14,INT_X0Y0/INT.INT_NODE_IMUX_50_INT_OUT0<<->>BYPASS_W14",
            "xcvu3p,INT_X0Y0/INT_NODE_IMUX_50_INT_OUT0,",
    })
    public void testGetAllDownhillPIPsReversed(String deviceName, String startNodeName, String reversedPIPString) {
        Device d = Device.getDevice(deviceName);
        Node startNode = d.getNode(startNodeName);
        for (PIP pip : startNode.getAllDownhillPIPs()) {
            if (pip.toString().equals(reversedPIPString)) {
                Assertions.assertTrue(pip.isReversed());
            } else {
                Assertions.assertFalse(pip.isReversed());
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
            "xcvu3p,INT_X0Y0/INT_NODE_IMUX_50_INT_OUT0,INT_X0Y0/INT.INT_NODE_IMUX_50_INT_OUT0<<->>BYPASS_W14",
            "xcvu3p,INT_X0Y0/BYPASS_W14,",
    })
    public void testGetAllUphillPIPsReversed(String deviceName, String endNodeName, String reversedPIPString) {
        Device d = Device.getDevice(deviceName);
        Node endNode = d.getNode(endNodeName);
        for (PIP pip : endNode.getAllUphillPIPs()) {
            if (pip.toString().equals(reversedPIPString)) {
                Assertions.assertTrue(pip.isReversed());
            } else {
                Assertions.assertFalse(pip.isReversed());
            }
        }
    }
}

