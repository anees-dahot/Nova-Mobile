package com.anees.novamobile.ui.common

/** Implemented by tab screens so the shell can handle Back like Apple TV. */
interface TabPage {
    /** Return true if the page moved focus/scroll back to its top (so Back shouldn't leave yet). */
    fun scrollToTop(): Boolean = false
}
