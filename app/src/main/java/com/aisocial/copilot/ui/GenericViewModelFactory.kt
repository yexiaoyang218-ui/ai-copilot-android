package com.aisocial.copilot.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/** Minimal ViewModel factory so the manual DI graph can build VMs without Hilt. */
class GenericViewModelFactory<T : ViewModel>(private val creator: () -> T) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = creator() as VM
}
