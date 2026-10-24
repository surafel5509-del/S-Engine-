package com.sengine.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import com.sengine.engine.blueprint.Blueprint

/** Node-graph canvas for blueprints - stub implementation. */
@SuppressLint("ViewConstructor")
class BlueprintView(ctx: Context, var bp: Blueprint) : View(ctx) {
    var onEditParam: (Any, String) -> Unit = { _, _ -> }
    var onNodeMenu: (Any) -> Unit = {}
    var onChanged: () -> Unit = {}

    var offX = 20f
    var offY = 20f
    var scale = 1f
    var selected: Any? = null

    // Stub implementation - full implementation to be added later
}
