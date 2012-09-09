/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.media;

import android.app.KeyguardManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.mtp.MtpDatabase;
import android.mtp.MtpServer;
import android.mtp.MtpStorage;
import android.os.Environment;
import android.os.IBinder;
import android.os.storage.StorageEventListener;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Log;

import java.io.File;
import java.util.HashMap;

public class MtpService extends Service {
    private static final String TAG = "MtpService";

    // We restrict PTP to these subdirectories
    private static final String[] PTP_DIRECTORIES = new String[] {
        Environment.DIRECTORY_DCIM,
        Environment.DIRECTORY_PICTURES,
    };

    private void addStorageDevicesLocked() {
        if (mPtpMode) {
            // In PTP mode we support only primary storage
            final StorageVolume primary = StorageManager.getPrimaryVolume(mVolumes);
            String path = primary.getPath();
            if (path != null) {
                String state = mStorageManager.getVolumeState(path);
                if (Environment.MEDIA_MOUNTED.equals(state)) {
                    addStorageLocked(mVolumeMap.get(primary.getPath()));
                }
            }
        } else {
            for (StorageVolume volume : mVolumeMap.values()) {
                addStorageLocked(volume);
            }
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Intent.ACTION_USER_PRESENT.equals(action)) {
                synchronized (mBinder) {
                    // Unhide the storage units when the user has unlocked the lockscreen
                    if (mMtpDisabled) {
                        addStorageDevicesLocked();
                        mMtpDisabled = false;
                    }
                }
            }
        }
    };

    private final StorageEventListener mStorageEventListener = new StorageEventListener() {
        public void onStorageStateChanged(String path, String oldState, String newState) {
            synchronized (mBinder) {
                Log.d(TAG, "onStorageStateChanged " + path + " " + oldState + " -> " + newState);
                if (Environment.MEDIA_MOUNTED.equals(newState)) {
                    volumeMountedLocked(path);
                } else if (Environment.MEDIA_MOUNTED.equals(oldState)) {
                    StorageVolume volume = mVolumeMap.remove(path);
                    if (volume != null) {
                        removeStorageLocked(volume);
                    }
                }
            }
        }
    };

    private MtpDatabase mDatabase;
    private MtpServer mServer;
    private StorageManager mStorageManager;
    private boolean mMtpDisabled; // true if MTP is disabled due to secure keyguard
    private boolean mPtpMode;
    private final HashMap<String, StorageVolume> mVolumeMap = new HashMap<String, StorageVolume>();
    private final HashMap<String, MtpStorage> mStorageMap = new HashMap<String, MtpStorage>();
    private StorageVolume[] mVolumes;

    @Override
    public void onCreate() {
        // lock MTP if the keyguard is locked and secure
        KeyguardManager keyguardManager =
                (KeyguardManager)getSystemService(Context.KEYGUARD_SERVICE);
        mMtpDisabled = keyguardManager.isKeyguardLocked() && keyguardManager.isKeyguardSecure();
        registerReceiver(mReceiver, new IntentFilter(Intent.ACTION_USER_PRESENT));

        mStorageManager = (StorageManager)getSystemService(Context.STORAGE_SERVICE);
        synchronized (mBinder) {
            mStorageManager.registerListener(mStorageEventListener);
            StorageVolume[] volumes = mStorageManager.getVolumeList();
            mVolumes = volumes;
            for (int i = 0; i < volumes.length; i++) {
                String path = volumes[i].getPath();
                String state = mStorageManager.getVolumeState(path);
                if (Environment.MEDIA_MOUNTED.equals(state)) {
                   volumeMountedLocked(path);
                }
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        synchronized (mBinder) {
            if (mServer == null) {
                mPtpMode = (intent == null ? false
                        : intent.getBooleanExtra(UsbManager.USB_FUNCTION_PTP, false));
                Log.d(TAG, "starting MTP server in " + (mPtpMode ? "PTP mode" : "MTP mode"));
                String[] subdirs = null;
                if (mPtpMode) {
                    int count = PTP_DIRECTORIES.length;
                    subdirs = new String[count];
                    for (int i = 0; i < count; i++) {
                        File file =
                                Environment.getExternalStoragePublicDirectory(PTP_DIRECTORIES[i]);
                        // make sure this directory exists
                        file.mkdirs();
                        subdirs[i] = file.getPath();
                    }
                }
                final StorageVolume primary = StorageManager.getPrimaryVolume(mVolumes);
                mDatabase = new MtpDatabase(this, MediaProvider.EXTERNAL_VOLUME,
                        primary.getPath(), subdirs);
                mServer = new MtpServer(mDatabase, mPtpMode);
                if (!mMtpDisabled) {
                    addStorageDevicesLocked();
                }
                mServer.start();
            }
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy()
    {
        unregisterReceiver(mReceiver);
        mStorageManager.unregisterListener(mStorageEventListener);
    }

    private final IMtpService.Stub mBinder =
            new IMtpService.Stub() {
        public void sendObjectAdded(int objectHandle) {
            synchronized (mBinder) {
                if (mServer != null) {
                    mServer.sendObjectAdded(objectHandle);
                }
            }
        }

        public void sendObjectRemoved(int objectHandle) {
            synchronized (mBinder) {
                if (mServer != null) {
                    mServer.sendObjectRemoved(objectHandle);
                }
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent)
    {
        return mBinder;
    }

    private void volumeMountedLocked(String path) {
        for (int i = 0; i < mVolumes.length; i++) {
            StorageVolume volume = mVolumes[i];
            if (volume.getPath().equals(path)) {
                mVolumeMap.put(path, volume);
                if (!mMtpDisabled) {
                    // In PTP mode we support only primary storage
                    if (volume.isPrimary() || !mPtpMode) {
                        addStorageLocked(volume);
                    }
                }
                break;
            }
        }
    }

    private void addStorageLocked(StorageVolume volume) {
        MtpStorage storage = new MtpStorage(volume, getApplicationContext());
        String path = storage.getPath();
        mStorageMap.put(path, storage);
        
        Log.d(TAG, "addStorageLocked " + storage.getStorageId() + " " +path);
        if (mDatabase != null) {
            mDatabase.addStorage(storage);
        }
        if (mServer != null) {
            mServer.addStorage(storage);
        }
    }

    private void removeStorageLocked(StorageVolume volume) {
        MtpStorage storage = mStorageMap.remove(volume.getPath());
        if (storage == null) {
            Log.e(TAG, "no MtpStorage for " + volume.getPath());
            return;
        }

        Log.d(TAG, "removeStorageLocked " + storage.getStorageId() + " " + storage.getPath());
        if (mDatabase != null) {
            mDatabase.removeStorage(storage);
        }
        if (mServer != null) {
            mServer.removeStorage(storage);
        }
    }
}

