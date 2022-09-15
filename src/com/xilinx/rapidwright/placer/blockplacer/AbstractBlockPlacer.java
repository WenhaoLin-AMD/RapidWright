/*
 * Copyright (c) 2021 Xilinx, Inc.
 * All rights reserved.
 *
 * Author: Jakob Wenzel, Xilinx Research Labs.
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
package com.xilinx.rapidwright.placer.blockplacer;

import com.xilinx.rapidwright.design.AbstractModuleInst;

/**
 * Base class of block placers.
 *
 * This can only apply moves on modules.
 * @param <ModuleInstT> the type of module instance
 * @param <PlacementT> the type of placement
 */
public abstract class AbstractBlockPlacer<ModuleInstT extends AbstractModuleInst<?,?,?>, PlacementT> {
    public abstract void setTempAnchorSite(ModuleInstT hm, PlacementT placement);
}
