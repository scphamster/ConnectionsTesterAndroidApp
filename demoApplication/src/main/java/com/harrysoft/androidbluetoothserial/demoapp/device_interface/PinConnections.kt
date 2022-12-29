package com.harrysoft.androidbluetoothserial.demoapp.device_interface

import androidx.lifecycle.MutableLiveData

data class PinConnections(val pin: PinNumberT, var connections: MutableList<PinNumberT>) {}
