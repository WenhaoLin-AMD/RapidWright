package com.xilinx.rapidwright.rwroute;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetTools;
import com.xilinx.rapidwright.util.VivadoToolsHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

public class TestNetTools {
    @Test
    public void testIsClockNet() {
        // two global nets in corescore_500 design reported by Vivado
        HashSet<String> clkNetNames = new HashSet<>(List.of(
            "clk_BUFG", 
            "clock_gen/rst"
        ));

        Design design = Design.readCheckpoint("/proj/xcohdstaff7/wenhalin/project/RapidWright/dataset/versal/corescore_500_versal_routed.dcp");
        List<Net> clkNets = new ArrayList<>();
        for (Net net: design.getNets()) {
            if (NetTools.isClockNet(net)) {
                clkNets.add(net);
            }
        }

        for (Net clkNet: clkNets) {
            if (clkNetNames.contains(clkNet.getName())) {
                clkNetNames.remove(clkNet.getName());
            }
        }
        Assertions.assertTrue(clkNetNames.isEmpty());
    }
}
