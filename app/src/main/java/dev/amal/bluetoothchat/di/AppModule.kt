package dev.amal.bluetoothchat.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.amal.bluetoothchat.data.chat.AndroidBluetoothController
import dev.amal.bluetoothchat.domain.chat.BluetoothController
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class AppModule {

    @Provides
    @Singleton
    fun provideBluetoothController(
        @ApplicationContext context: Context
    ): BluetoothController = AndroidBluetoothController(context = context)
}