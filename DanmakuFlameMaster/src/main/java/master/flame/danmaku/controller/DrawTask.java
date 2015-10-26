/*
 * Copyright (C) 2013 Chen Hui <calmer91@gmail.com>
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

package master.flame.danmaku.controller;

import android.graphics.Canvas;

import master.flame.danmaku.danmaku.model.AbsDisplayer;
import master.flame.danmaku.danmaku.model.BaseDanmaku;
import master.flame.danmaku.danmaku.model.DanmakuTimer;
import master.flame.danmaku.danmaku.model.IDanmakuIterator;
import master.flame.danmaku.danmaku.model.IDanmakus;
import master.flame.danmaku.danmaku.model.android.DanmakuContext;
import master.flame.danmaku.danmaku.model.android.DanmakuContext.ConfigChangedCallback;
import master.flame.danmaku.danmaku.model.android.DanmakuContext.DanmakuConfigTag;
import master.flame.danmaku.danmaku.model.android.Danmakus;
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser;
import master.flame.danmaku.danmaku.renderer.IRenderer;
import master.flame.danmaku.danmaku.renderer.IRenderer.RenderingState;
import master.flame.danmaku.danmaku.renderer.android.DanmakuRenderer;

public class DrawTask implements IDrawTask {

    protected final DanmakuContext mContext;
    
    protected final AbsDisplayer mDisp;

    protected IDanmakus danmakuList;

    protected BaseDanmakuParser mParser;

    TaskListener mTaskListener;

    IRenderer mRenderer;

    DanmakuTimer mTimer;

    private IDanmakus danmakus = new Danmakus(Danmakus.ST_BY_LIST);

    protected boolean clearRetainerFlag;

    private long mStartRenderTime = 0;

    private RenderingState mRenderingState = new RenderingState();

    protected boolean mReadyState;

    private long mLastBeginMills;

    private long mLastEndMills;

    private boolean mIsHidden;
    private ConfigChangedCallback mConfigChangedCallback = new ConfigChangedCallback() {
        @Override
        public boolean onDanmakuConfigChanged(DanmakuContext config, DanmakuConfigTag tag, Object... values) {
            return DrawTask.this.onDanmakuConfigChanged(config, tag, values);
        }
    };

    public DrawTask(DanmakuTimer timer, DanmakuContext context,
            TaskListener taskListener) {
        if (context == null) {
            throw new IllegalArgumentException("context is null");
        }
        mContext = context;
        mDisp = context.getDisplayer();
        mTaskListener = taskListener;
        mRenderer = new DanmakuRenderer(context);
        mRenderer.setVerifierEnabled(mContext.isPreventOverlappingEnabled() || mContext.isMaxLinesLimited());
        initTimer(timer);
        Boolean enable = mContext.isDuplicateMergingEnabled();
        if (enable != null) {
            if(enable) {
                mContext.mDanmakuFilters.registerFilter(DanmakuFilters.TAG_DUPLICATE_FILTER);
            } else {
                mContext.mDanmakuFilters.unregisterFilter(DanmakuFilters.TAG_DUPLICATE_FILTER);
            }
        }
    }

    protected void initTimer(DanmakuTimer timer) {
        mTimer = timer;
    }

    @Override
    public synchronized void addDanmaku(BaseDanmaku item) {
        if (danmakuList == null)
            return;
        boolean added = false;
        if (item.isLive) {
            removeUnusedLiveDanmakusIn(10);
        }
        item.index = danmakuList.size();
        if (mLastBeginMills <= item.time && item.time <= mLastEndMills) {
            synchronized (danmakus) {
                added = danmakus.addItem(item);
            }
        } else if (item.isLive) {
            mLastBeginMills = mLastEndMills = 0;
        }
        synchronized (danmakuList) {
            added = danmakuList.addItem(item);
        }
        if (added && mTaskListener != null) {
            mTaskListener.onDanmakuAdd(item);
        }
    }
    
    @Override
    public synchronized void removeAllDanmakus() {
        if (danmakuList == null || danmakuList.isEmpty())
            return;
        danmakuList.clear();
    }

    protected void onDanmakuRemoved(BaseDanmaku danmaku) {
        // TODO call callback here
    }

    @Override
    public synchronized void removeAllLiveDanmakus() {
        if (danmakus == null || danmakus.isEmpty())
            return;
        synchronized (danmakus) {
            IDanmakuIterator it = danmakus.iterator();
            while (it.hasNext()) {
                BaseDanmaku danmaku = it.next();
                if (danmaku.isLive) {
                    it.remove();
                    onDanmakuRemoved(danmaku);
                }
            }
        }
    }

    protected synchronized void removeUnusedLiveDanmakusIn(int msec) {
        if (danmakuList == null || danmakuList.isEmpty())
            return;
        long startTime = System.currentTimeMillis();
        IDanmakuIterator it = danmakuList.iterator();
        while (it.hasNext()) {
            BaseDanmaku danmaku = it.next();
            boolean isTimeout = danmaku.isTimeOut();
            if (isTimeout && danmaku.isLive) {
                it.remove();
                onDanmakuRemoved(danmaku);
            }
            if (!isTimeout || System.currentTimeMillis() - startTime > msec) {
                break;
            }
        }
    }

    @Override
    public IDanmakus getVisibleDanmakusOnTime(long time) {
        long beginMills = time - mContext.mDanmakuFactory.MAX_DANMAKU_DURATION - 100;
        long endMills = time + mContext.mDanmakuFactory.MAX_DANMAKU_DURATION;
        IDanmakus subDanmakus = danmakuList.sub(beginMills, endMills);
        IDanmakus visibleDanmakus = new Danmakus();
        if (null != subDanmakus && !subDanmakus.isEmpty()) {
            IDanmakuIterator iterator = subDanmakus.iterator();
            while (iterator.hasNext()) {
                BaseDanmaku danmaku = iterator.next();
                if (danmaku.isShown() && !danmaku.isOutside()) {
                    visibleDanmakus.addItem(danmaku);
                }
            }
        }

        return visibleDanmakus;
    }

    @Override
    public synchronized RenderingState draw(AbsDisplayer displayer) {
        return drawDanmakus(displayer,mTimer);
    }

    @Override
    public void reset() {
        if (danmakus != null)
            danmakus.clear();
        if (mRenderer != null)
            mRenderer.clear();
    }

    @Override
    public void seek(long mills) {
        reset();
//        requestClear();
        mContext.mGlobalFlagValues.updateVisibleFlag();
        mStartRenderTime = mills < 1000 ? 0 : mills;
    }

    @Override
    public void clearDanmakusOnScreen(long currMillis) {
        reset();
        mContext.mGlobalFlagValues.updateVisibleFlag();
        mStartRenderTime = currMillis;
    }

    @Override
    public void start() {
        mContext.registerConfigChangedCallback(mConfigChangedCallback);
    }

    @Override
    public void quit() {
        mContext.unregisterAllConfigChangedCallbacks();
        if (mRenderer != null)
            mRenderer.release();
    }

    public void prepare() {
        assert (mParser != null);
        loadDanmakus(mParser);
        if (mTaskListener != null) {
            mTaskListener.ready();
            mReadyState = true;
        }
    }

    protected void loadDanmakus(BaseDanmakuParser parser) {
        danmakuList = parser.setConfig(mContext).setDisplayer(mDisp).setTimer(mTimer).getDanmakus();
        if (danmakuList != null && !danmakuList.isEmpty()) {
            if (danmakuList.first().flags == null) {
                IDanmakuIterator it = danmakuList.iterator();
                while (it.hasNext()) {
                    BaseDanmaku item = it.next();
                    if (item != null) {
                        item.flags = mContext.mGlobalFlagValues;
                    }
                }
            }
        }
        mContext.mGlobalFlagValues.resetAll();
    }

    public void setParser(BaseDanmakuParser parser) {
        mParser = parser;
        mReadyState = false;
    }

    protected RenderingState drawDanmakus(AbsDisplayer disp, DanmakuTimer timer) {
        if (clearRetainerFlag) {
            mRenderer.clearRetainer();
            clearRetainerFlag = false;
        }
        if (danmakuList != null) {
            Canvas canvas = (Canvas) disp.getExtraData();
            DrawHelper.clearCanvas(canvas);
            if (mIsHidden) {
                return mRenderingState;
            }
            long beginMills = timer.currMillisecond - mContext.mDanmakuFactory.MAX_DANMAKU_DURATION - 100;
            long endMills = timer.currMillisecond + mContext.mDanmakuFactory.MAX_DANMAKU_DURATION;
            if(mLastBeginMills > beginMills || timer.currMillisecond > mLastEndMills) {
                IDanmakus subDanmakus = danmakuList.sub(beginMills, endMills);
                if(subDanmakus != null) {
                    danmakus = subDanmakus;
                } else {
                    danmakus.clear();
                }
                mLastBeginMills = beginMills;
                mLastEndMills = endMills;
            } else {
                beginMills = mLastBeginMills;
                endMills = mLastEndMills;
            }
            if (danmakus != null && !danmakus.isEmpty()) {
                RenderingState renderingState = mRenderingState = mRenderer.draw(mDisp, danmakus, mStartRenderTime);
                if (renderingState.nothingRendered) {
                    if (renderingState.beginTime == RenderingState.UNKNOWN_TIME) {
                        renderingState.beginTime = beginMills;
                    }
                    if (renderingState.endTime == RenderingState.UNKNOWN_TIME) {
                        renderingState.endTime = endMills;
                    }
                }
                return renderingState;
            } else {
                mRenderingState.nothingRendered = true;
                mRenderingState.beginTime = beginMills;
                mRenderingState.endTime = endMills;
                return mRenderingState;
            }
        }
        return null;
    }

    public void requestClear() {
        mLastBeginMills = mLastEndMills = 0;
        mIsHidden = false;
    }

    public void requestClearRetainer() {
        clearRetainerFlag = true;
    }

    public boolean onDanmakuConfigChanged(DanmakuContext config, DanmakuConfigTag tag,
            Object... values) {
        boolean handled = handleOnDanmakuConfigChanged(config, tag, values);
        if (mTaskListener != null) {
            mTaskListener.onDanmakuConfigChanged();
        }
        return handled;
    }

    protected boolean handleOnDanmakuConfigChanged(DanmakuContext config, DanmakuConfigTag tag, Object[] values) {
        boolean handled = false;
        if (tag == null || DanmakuConfigTag.MAXIMUM_NUMS_IN_SCREEN.equals(tag)) {
            handled = true;
        } else if (DanmakuConfigTag.DUPLICATE_MERGING_ENABLED.equals(tag)) {
            Boolean enable = (Boolean) values[0];
            if (enable != null) {
                if (enable) {
                    mContext.mDanmakuFilters.registerFilter(DanmakuFilters.TAG_DUPLICATE_FILTER);
                } else {
                    mContext.mDanmakuFilters.unregisterFilter(DanmakuFilters.TAG_DUPLICATE_FILTER);
                }
                handled = true;
            }
        } else if (DanmakuConfigTag.SCALE_TEXTSIZE.equals(tag) || DanmakuConfigTag.SCROLL_SPEED_FACTOR.equals(tag)) {
            requestClearRetainer();
            handled = false;
        } else if (DanmakuConfigTag.MAXIMUN_LINES.equals(tag) || DanmakuConfigTag.OVERLAPPING_ENABLE.equals(tag)) {
            if (mRenderer != null) {
                mRenderer.setVerifierEnabled(mContext.isPreventOverlappingEnabled() || mContext.isMaxLinesLimited());
            }
            handled = true;
        }
        return handled;
    }

    @Override
    public void requestHide() {
        mIsHidden = true;
    }

}
