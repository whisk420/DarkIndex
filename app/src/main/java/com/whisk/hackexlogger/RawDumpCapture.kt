package com.whisk.hackexlogger

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.pm.PackageInfoCompat

/** Android-only helpers for Raw Dump Mode (kept out of RawDump.kt so that file stays JVM-testable). */
object RawDumpCapture {
    /** Safety valve against pathological trees; real HackEx screens are a few hundred nodes. */
    private const val MAX_NODES = 20_000

    fun appInfo(context: Context): RawDumpAppInfo = try {
        @Suppress("DEPRECATION")
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        RawDumpAppInfo(pi.versionName, PackageInfoCompat.getLongVersionCode(pi))
    } catch (e: Exception) {
        RawDumpAppInfo(null, -1)
    }

    /** Flattened pre-order snapshot of the accessibility tree. Must run on the main thread with the scrape. */
    fun nodeTree(root: AccessibilityNodeInfo): List<RawDumpNode> {
        val out = ArrayList<RawDumpNode>(256)
        val rect = Rect()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || out.size >= MAX_NODES) return
            node.getBoundsInScreen(rect)
            out.add(
                RawDumpNode(
                    d = depth,
                    cls = node.className?.toString(),
                    id = node.viewIdResourceName,
                    text = node.text?.toString(),
                    desc = node.contentDescription?.toString(),
                    bounds = listOf(rect.left, rect.top, rect.right, rect.bottom)
                )
            )
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out
    }
}
