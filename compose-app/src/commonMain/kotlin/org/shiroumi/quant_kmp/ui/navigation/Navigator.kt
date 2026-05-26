package org.shiroumi.quant_kmp.ui.navigation

import org.shiroumi.quant_kmp.NavDest

class Navigator(val state: NavigationState) {

    fun navigate(route: NavDest) {
        val matchingTopLevelRoute = state.backStacks.keys.find { topLevelRoute ->
            route::class == topLevelRoute::class
        }

        if (matchingTopLevelRoute != null) {
            state.topLevelRoute = matchingTopLevelRoute
            if (route != matchingTopLevelRoute) {
                state.backStacks[matchingTopLevelRoute]?.add(route)
            }
        } else {
            state.backStacks[state.topLevelRoute]?.add(route)
        }
    }

    fun goBack(): Boolean {
        // 优先消费已打开的临时返回项（如 supporting pane），保证“浏览器/系统 Back 先关 pane”。
        if (state.consumeTransientBack()) return true

        val currentStack = state.backStacks[state.topLevelRoute]
            ?: error("Stack for ${state.topLevelRoute} not found")

        val currentRoute = currentStack.lastOrNull()

        return if (currentRoute == state.topLevelRoute) {
            if (state.topLevelRoute != state.startRoute) {
                state.topLevelRoute = state.startRoute
                true
            } else {
                false
            }
        } else {
            currentStack.removeLastOrNull() != null
        }
    }
}
