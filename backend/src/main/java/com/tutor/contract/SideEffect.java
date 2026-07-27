package com.tutor.contract;

/** 工具副作用等级 (实现设计 7.3): L0 只读 / L1 写内部数据 / L2 外部动作(必须过确认闸) */
public enum SideEffect {
    L0, L1, L2
}
