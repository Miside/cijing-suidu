package com.yy.suidu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider

class MobileActivity : ComponentActivity() {
    private lateinit var model: MobileModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[MobileModel::class.java]
        setContent { MobileApp(model) }
    }
    override fun onResume() { super.onResume(); if (::model.isInitialized) model.refreshNow() }
}
