/*
 * Copyright (c) 2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Wenhao Lin, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.design;

import java.util.HashSet;

import com.xilinx.rapidwright.device.Series;

public class NetTools {
    private static HashSet<String> clkSourceNamesOfUS = new HashSet<>();
    static {
        clkSourceNamesOfUS.add("CLK_OUT");
        clkSourceNamesOfUS.add("CLKOUT");
        clkSourceNamesOfUS.add("CLKFBOUT");
    }
    private static HashSet<String> clkSourceNamesOfVersal = new HashSet<>();
    static {
        clkSourceNamesOfVersal.add("O");
    }
    
    public static boolean isClockNet(Net net) {
        Series series = net.getDesign().getDevice().getSeries();
        SitePinInst source = net.getSource();
        if (source == null)
            return false;        
        String sourceName = source.getName();
        
        if (series == Series.UltraScale || series == Series.UltraScalePlus) {
            return clkSourceNamesOfUS.contains(sourceName);
        }
        if (series == Series.Versal) {
            String tileName = source.getTile().getName();
            String siteName = source.getSite().toString();
            return tileName.startsWith("CLK") && siteName.startsWith("BUF") && clkSourceNamesOfVersal.contains(sourceName);
        }
        // fallback
        return net.isClockNet();
    }

    public static boolean isGlobalNet(Net net) {
        return net.isStaticNet() || isClockNet(net);
    }
}
