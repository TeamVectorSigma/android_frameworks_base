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

package android.app;

import android.content.Loader;
import android.content.Loader.OnLoadCompleteListener;
import android.os.Bundle;
import android.util.SparseArray;

/**
 * Object associated with an {@link Activity} or {@link Fragment} for managing
 * one or more {@link android.content.Loader} instances associated with it.
 */
public class LoaderManager {
    final SparseArray<LoaderInfo> mLoaders = new SparseArray<LoaderInfo>();
    final SparseArray<LoaderInfo> mInactiveLoaders = new SparseArray<LoaderInfo>();
    boolean mStarted;
    boolean mRetaining;
    boolean mRetainingStarted;
    
    /**
     * Callback interface for a client to interact with the manager.
     */
    public interface LoaderCallbacks<D> {
        public Loader<D> onCreateLoader(int id, Bundle args);
        public void onLoadFinished(Loader<D> loader, D data);
    }
    
    final class LoaderInfo implements Loader.OnLoadCompleteListener<Object> {
        final int mId;
        final Bundle mArgs;
        LoaderManager.LoaderCallbacks<Object> mCallbacks;
        Loader<Object> mLoader;
        Object mData;
        boolean mStarted;
        boolean mRetaining;
        boolean mRetainingStarted;
        boolean mDestroyed;
        boolean mListenerRegistered;
        
        public LoaderInfo(int id, Bundle args, LoaderManager.LoaderCallbacks<Object> callbacks) {
            mId = id;
            mArgs = args;
            mCallbacks = callbacks;
        }
        
        void start() {
            if (mRetaining && mRetainingStarted) {
                // Our owner is started, but we were being retained from a
                // previous instance in the started state...  so there is really
                // nothing to do here, since the loaders are still started.
                mStarted = true;
                return;
            }

            if (mLoader == null && mCallbacks != null) {
               mLoader = mCallbacks.onCreateLoader(mId, mArgs);
            }
            if (mLoader != null) {
                mLoader.registerListener(mId, this);
                mListenerRegistered = true;
                mLoader.startLoading();
                mStarted = true;
            }
        }
        
        void retain() {
            mRetaining = true;
            mRetainingStarted = mStarted;
            mStarted = false;
            mCallbacks = null;
        }
        
        void finishRetain() {
            if (mRetaining) {
                mRetaining = false;
                if (mStarted != mRetainingStarted) {
                    if (!mStarted) {
                        // This loader was retained in a started state, but
                        // at the end of retaining everything our owner is
                        // no longer started...  so make it stop.
                        stop();
                    }
                }
                if (mStarted && mData != null && mCallbacks != null) {
                    // This loader was retained, and now at the point of
                    // finishing the retain we find we remain started, have
                    // our data, and the owner has a new callback...  so
                    // let's deliver the data now.
                    mCallbacks.onLoadFinished(mLoader, mData);
                }
            }
        }
        
        void stop() {
            mStarted = false;
            if (mLoader != null && mListenerRegistered) {
                // Let the loader know we're done with it
                mListenerRegistered = false;
                mLoader.unregisterListener(this);
            }
        }
        
        void destroy() {
            mDestroyed = true;
            mCallbacks = null;
            if (mLoader != null) {
                if (mListenerRegistered) {
                    mListenerRegistered = false;
                    mLoader.unregisterListener(this);
                }
                mLoader.destroy();
            }
        }
        
        @Override public void onLoadComplete(Loader<Object> loader, Object data) {
            if (mDestroyed) {
                return;
            }
            
            // Notify of the new data so the app can switch out the old data before
            // we try to destroy it.
            mData = data;
            if (mCallbacks != null) {
                mCallbacks.onLoadFinished(loader, data);
            }

            // Look for an inactive loader and destroy it if found
            LoaderInfo info = mInactiveLoaders.get(mId);
            if (info != null) {
                Loader<Object> oldLoader = info.mLoader;
                if (oldLoader != null) {
                    oldLoader.unregisterListener(info);
                    oldLoader.destroy();
                }
                mInactiveLoaders.remove(mId);
            }
        }
    }
    
    LoaderManager(boolean started) {
        mStarted = started;
    }
    
    private LoaderInfo createLoader(int id, Bundle args,
            LoaderManager.LoaderCallbacks<Object> callback) {
        LoaderInfo info = new LoaderInfo(id, args,  (LoaderManager.LoaderCallbacks<Object>)callback);
        mLoaders.put(id, info);
        Loader<Object> loader = callback.onCreateLoader(id, args);
        info.mLoader = (Loader<Object>)loader;
        if (mStarted) {
            // The activity will start all existing loaders in it's onStart(), so only start them
            // here if we're past that point of the activitiy's life cycle
            loader.registerListener(id, info);
            loader.startLoading();
        }
        return info;
    }
    
    /**
     * Ensures a loader is initialized an active.  If the loader doesn't
     * already exist, one is created and started.  Otherwise the last created
     * loader is re-used.
     * 
     * <p>In either case, the given callback is associated with the loader, and
     * will be called as the loader state changes.  If at the point of call
     * the caller is in its started state, and the requested loader
     * already exists and has generated its data, then
     * callback.{@link LoaderCallbacks#onLoadFinished(Loader, Object)} will 
     * be called immediately (inside of this function), so you must be prepared
     * for this to happen.
     */
    @SuppressWarnings("unchecked")
    public <D> Loader<D> initLoader(int id, Bundle args, LoaderManager.LoaderCallbacks<D> callback) {
        LoaderInfo info = mLoaders.get(id);
        
        if (info == null) {
            // Loader doesn't already exist; create.
            info = createLoader(id, args,  (LoaderManager.LoaderCallbacks<Object>)callback);
        } else {
            info.mCallbacks = (LoaderManager.LoaderCallbacks<Object>)callback;
        }
        
        if (info.mData != null && mStarted) {
            // If the loader has already generated its data, report it now.
            info.mCallbacks.onLoadFinished(info.mLoader, info.mData);
        }
        
        return (Loader<D>)info.mLoader;
    }
    
    /**
     * Create a new loader in this manager, registers the callbacks to it,
     * and starts it loading.  If a loader with the same id has previously been
     * started it will automatically be destroyed when the new loader completes
     * its work. The callback will be delivered before the old loader
     * is destroyed.
     */
    @SuppressWarnings("unchecked")
    public <D> Loader<D> restartLoader(int id, Bundle args, LoaderManager.LoaderCallbacks<D> callback) {
        LoaderInfo info = mLoaders.get(id);
        if (info != null) {
            if (mInactiveLoaders.get(id) != null) {
                // We already have an inactive loader for this ID that we are
                // waiting for!  Now we have three active loaders... let's just
                // drop the one in the middle, since we are still waiting for
                // its result but that result is already out of date.
                info.destroy();
            } else {
                // Keep track of the previous instance of this loader so we can destroy
                // it when the new one completes.
                mInactiveLoaders.put(id, info);
            }
        }
        
        info = createLoader(id, args,  (LoaderManager.LoaderCallbacks<Object>)callback);
        return (Loader<D>)info.mLoader;
    }
    
    /**
     * Stops and removes the loader with the given ID.
     */
    public void stopLoader(int id) {
        int idx = mLoaders.indexOfKey(id);
        if (idx >= 0) {
            LoaderInfo info = mLoaders.valueAt(idx);
            mLoaders.removeAt(idx);
            Loader<Object> loader = info.mLoader;
            if (loader != null) {
                loader.unregisterListener(info);
                loader.destroy();
            }
        }
    }

    /**
     * Return the Loader with the given id or null if no matching Loader
     * is found.
     */
    @SuppressWarnings("unchecked")
    public <D> Loader<D> getLoader(int id) {
        LoaderInfo loaderInfo = mLoaders.get(id);
        if (loaderInfo != null) {
            return (Loader<D>)mLoaders.get(id).mLoader;
        }
        return null;
    }
 
    void doStart() {
        // Call out to sub classes so they can start their loaders
        // Let the existing loaders know that we want to be notified when a load is complete
        for (int i = mLoaders.size()-1; i >= 0; i--) {
            mLoaders.valueAt(i).start();
        }
        mStarted = true;
    }
    
    void doStop() {
        for (int i = mLoaders.size()-1; i >= 0; i--) {
            mLoaders.valueAt(i).stop();
        }
        mStarted = false;
    }
    
    void doRetain() {
        mRetaining = true;
        mStarted = false;
        for (int i = mLoaders.size()-1; i >= 0; i--) {
            mLoaders.valueAt(i).retain();
        }
    }
    
    void finishRetain() {
        mRetaining = false;
        for (int i = mLoaders.size()-1; i >= 0; i--) {
            mLoaders.valueAt(i).finishRetain();
        }
    }
    
    void doDestroy() {
        if (!mRetaining) {
            for (int i = mLoaders.size()-1; i >= 0; i--) {
                mLoaders.valueAt(i).destroy();
            }
        }
        
        for (int i = mInactiveLoaders.size()-1; i >= 0; i--) {
            mInactiveLoaders.valueAt(i).destroy();
        }
        mInactiveLoaders.clear();
    }
}
