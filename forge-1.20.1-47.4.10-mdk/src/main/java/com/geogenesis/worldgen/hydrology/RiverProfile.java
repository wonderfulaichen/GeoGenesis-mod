package com.geogenesis.worldgen.hydrology;

/** 单个河网单元的纵剖面与横断面输入。 */
public record RiverProfile(int cell, int downstream, double elevation,
                           double slope, double discharge, RiverCrossSection section) {
}
