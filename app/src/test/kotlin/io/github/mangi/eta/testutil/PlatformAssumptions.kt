package io.github.mangi.eta.testutil

import org.junit.Assume

/**
 * 平台能力断言：把「Windows 上测不了」与「代码有 bug」区分开。
 *
 * 背景：本地开发在 Windows，CI 在 Linux。一批测试长期以红点形式挂在本地失败列表里，
 * 没人分辨得出哪条是平台限制、哪条是真 bug —— 结果真 bug（如记忆单例只绑首次 Application）
 * 混在红点里很久没被发现。
 *
 * 这些守卫的作用是把「平台限制」显式标注出来：本地跳过（skipped），CI 照常执行。
 * 红点数量因此等于「真正需要人看的问题数」，重新具备信号意义。
 */
internal object PlatformAssumptions {

    private val osName: String = System.getProperty("os.name").orEmpty().lowercase()

    val isWindows: Boolean get() = osName.contains("win")

    /**
     * 要求「文件 rename 可以覆盖已存在目标」。
     *
     * `AtomicFile` 写文件的方式是「写临时文件 → rename 覆盖正式文件」。Java 的
     * `File.renameTo` 在 Windows 上**目标已存在时返回 false 且不抛异常**，
     * 于是第二次写入会静默失败：内容没更新、revision 对不上，
     * 表现为「Clear 之后内容还在」「期望 Conflict 却拿到 Success」。
     *
     * Android 设备是 Linux 内核，rename 覆盖是正常语义，**产品行为不受影响**。
     * 因此这类用例在 Windows 上跳过，而不是改产品代码。
     */
    fun requireRenameOverwrite() {
        Assume.assumeFalse(
            "Windows 的 rename 不能覆盖已存在文件，AtomicFile 写入会静默失败；Android 设备不受影响",
            isWindows,
        )
    }

    /**
     * 要求 POSIX 文件语义（SQLite 的 `-wal` / `-shm` sidecar 可见性与删除行为）。
     *
     * Windows 上 SQLite 的 sidecar 生命周期不同，断言 sidecar 是否被一并复制会得出相反结论。
     */
    fun requirePosixFileSemantics() {
        Assume.assumeFalse(
            "SQLite sidecar 的可见性/删除语义在 Windows 上不同；Android 设备不受影响",
            isWindows,
        )
    }
}
