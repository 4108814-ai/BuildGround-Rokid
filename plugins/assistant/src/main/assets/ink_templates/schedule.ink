<script type="application/json" def>
{
  "data": {
    "title": "Schedule",
    "entries": [
      { "time": "09:00", "title": "Planning", "detail": "Project room" },
      { "time": "11:30", "title": "Review" },
      { "time": "14:00", "title": "Focus time", "detail": "90 minutes" }
    ]
  }
}
</script>

<page>
  <scroll-view class="page" scroll-y="true">
    <text class="title">{{ title }}</text>
    <view class="entries">
      <view class="entry" wx:for="{{ entries }}">
        <view class="time-cell">
          <text class="time">{{ item.time }}</text>
        </view>
        <view class="entry-copy">
          <text class="entry-title">{{ item.title }}</text>
          <text class="detail" wx:if="{{ item.detail }}">{{ item.detail }}</text>
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
}
.title {
  color: var(--color-primary);
  font-size: 34rpx;
  font-weight: 700;
  line-height: 40rpx;
}
.entries {
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}
.entry {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 12rpx;
  padding: 10rpx 8rpx;
  border-width: 1rpx;
  border-style: solid;
  border-color: var(--border-color-muted);
  border-radius: var(--radius-sm, 18rpx);
}
.time-cell {
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: center;
  width: 82rpx;
  flex-shrink: 0;
  padding: 7rpx 8rpx;
  border-width: 1rpx;
  border-style: solid;
  border-color: var(--border-color-default);
  border-radius: var(--radius-sm, 18rpx);
}
.time {
  color: var(--color-primary);
  font-size: 20rpx;
  font-weight: 700;
  line-height: 26rpx;
}
.entry-copy {
  display: flex;
  flex-direction: column;
  gap: 2rpx;
  flex-grow: 1;
  min-width: 0rpx;
}
.entry-title {
  color: var(--color-text-primary);
  font-size: 23rpx;
  font-weight: 600;
  line-height: 29rpx;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.detail {
  color: var(--color-text-secondary);
  font-size: 17rpx;
  line-height: 22rpx;
  opacity: 0.6;
}
</style>
