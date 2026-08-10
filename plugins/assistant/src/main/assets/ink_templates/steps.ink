<script type="application/json" def>
{
  "data": {
    "title": "Steps",
    "current": 1,
    "progressPercent": 33,
    "steps": [
      { "label": "Prepare", "detail": "Ready" },
      { "label": "Process", "detail": "In progress" },
      { "label": "Finish" }
    ]
  }
}
</script>

<page>
  <scroll-view class="page" scroll-y="true">
    <text class="title">{{ title }}</text>
    <view class="progress-frame">
      <progress class="progress" percent="{{ progressPercent }}" show-info="true" stroke-width="8" />
    </view>
    <view class="steps">
      <view class="step-slot" wx:for="{{ steps }}" wx:for-item="step" wx:for-index="stepIndex">
        <view class="step-completed" wx:if="{{ current > stepIndex }}">
          <view class="marker-completed">
            <text class="marker-text">{{ stepIndex + 1 }}</text>
          </view>
          <view class="step-copy">
            <text class="step-label">{{ step.label }}</text>
            <text class="step-detail" wx:if="{{ step.detail }}">{{ step.detail }}</text>
          </view>
        </view>
        <view class="step-current" wx:elif="{{ stepIndex === current }}">
          <view class="marker-current">
            <text class="marker-text">{{ stepIndex + 1 }}</text>
          </view>
          <view class="step-copy">
            <text class="step-label">{{ step.label }}</text>
            <text class="step-detail" wx:if="{{ step.detail }}">{{ step.detail }}</text>
          </view>
        </view>
        <view class="step-upcoming" wx:else>
          <view class="marker-upcoming">
            <text class="marker-text">{{ stepIndex + 1 }}</text>
          </view>
          <view class="step-copy">
            <text class="step-label">{{ step.label }}</text>
            <text class="step-detail" wx:if="{{ step.detail }}">{{ step.detail }}</text>
          </view>
        </view>
      </view>
    </view>
  </scroll-view>
</page>

<style>
.page {
  display: flex;
  flex-direction: column;
  gap: 14rpx;
  width: 100%;
  height: 550rpx;
  box-sizing: border-box;
  padding: 18rpx;
  color: var(--color-text-primary);
  background-color: var(--color-background);
}
.title {
  color: var(--color-primary);
  font-size: 34rpx;
  font-weight: 700;
  line-height: 40rpx;
}
.progress-frame {
  display: flex;
  flex-direction: column;
  width: 100%;
  box-sizing: border-box;
  padding: 8rpx 10rpx;
  border-width: 1rpx;
  border-style: solid;
  border-color: var(--border-color-muted);
  border-radius: var(--radius-sm, 18rpx);
}
.progress {
  width: 100%;
  height: 22rpx;
}
.steps {
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}
.step-slot {
  display: flex;
  flex-direction: column;
}
.step-completed {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 12rpx;
  padding: 10rpx;
  border-width: 1rpx;
  border-style: solid;
  border-color: var(--border-color-default);
  border-radius: var(--radius-sm, 18rpx);
  opacity: 0.72;
}
.step-current {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 12rpx;
  padding: 10rpx;
  border-width: 2rpx;
  border-style: solid;
  border-color: var(--border-color-accent);
  border-radius: var(--radius-sm, 18rpx);
}
.step-upcoming {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 12rpx;
  padding: 10rpx;
  border-width: 1rpx;
  border-style: solid;
  border-color: var(--border-color-muted);
  border-radius: var(--radius-sm, 18rpx);
  opacity: 0.46;
}
.marker-completed {
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: center;
  width: 34rpx;
  height: 34rpx;
  flex-shrink: 0;
  border-width: 1rpx;
  border-style: solid;
  border-color: var(--border-color-default);
  border-radius: var(--radius-sm, 18rpx);
}
.marker-current {
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: center;
  width: 34rpx;
  height: 34rpx;
  flex-shrink: 0;
  border-width: 2rpx;
  border-style: solid;
  border-color: var(--border-color-accent);
  border-radius: var(--radius-sm, 18rpx);
}
.marker-upcoming {
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: center;
  width: 34rpx;
  height: 34rpx;
  flex-shrink: 0;
  border-width: 1rpx;
  border-style: solid;
  border-color: var(--border-color-muted);
  border-radius: var(--radius-sm, 18rpx);
}
.marker-text {
  color: var(--color-primary);
  font-size: 18rpx;
  font-weight: 700;
  line-height: 22rpx;
}
.step-copy {
  display: flex;
  flex-direction: column;
  gap: 2rpx;
  flex-grow: 1;
  min-width: 0rpx;
}
.step-label {
  color: var(--color-text-primary);
  font-size: 22rpx;
  font-weight: 600;
  line-height: 28rpx;
}
.step-detail {
  color: var(--color-text-secondary);
  font-size: 17rpx;
  line-height: 22rpx;
  opacity: 0.62;
}
</style>
