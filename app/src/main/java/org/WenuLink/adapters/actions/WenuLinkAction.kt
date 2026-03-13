package org.WenuLink.adapters.actions

sealed class WenuLinkAction (
    open val onResult: (String?) -> Unit
)
