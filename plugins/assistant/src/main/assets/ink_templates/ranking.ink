<script type="application/json" def>
{
  "data": {
    "title": "Ranking",
    "rows": [
      { "label": "North", "value": "96", "detail": "Up 4" },
      { "label": "Central", "value": "89" },
      { "label": "South", "value": "84", "detail": "Steady" }
    ]
  }
}
</script>

<page>
  <scroll-view class="page" scroll-y="true">
    <text class="title">{{ title }}</text>
    <view class="rows">
      <view class="row" wx:for="{{ rows }}" wx:for-index="rowIndex">
        <view class="rank">
          <text class="rank-value">{{ rowIndex + 1 }}</text>
        </view>
        <view class="row-copy">
          <text class="row-label">{{ item.label }}</text>
          <text class="detail" wx:if="{{ item.detail }}">{{ item.detail }}</text>
        </view>
        <text class="value">{{ item.value }}</text>
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
  border-width: 2rpx;
  border-style: solid;
  border-color: var(--border-color-default);
  border-radius: var(--radius-md, 18rpx);
}
.title {
  color: var(--color-primary);
  font-size: 34rpx;
  font-weight: 700;
  line-height: 40rpx;
}
.rows {
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}
.row {
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
.rank {
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: center;
  width: 36rpx;
  height: 36rpx;
  flex-shrink: 0;
  border-width: 1rpx;
  border-style: solid;
  border-color: var(--border-color-default);
  border-radius: var(--radius-sm, 18rpx);
}
.rank-value {
  color: var(--color-primary);
  font-size: 19rpx;
  font-weight: 700;
  line-height: 24rpx;
}
.row-copy {
  display: flex;
  flex-direction: column;
  gap: 2rpx;
  flex-grow: 1;
  min-width: 0rpx;
}
.row-label {
  color: var(--color-text-primary);
  font-size: 23rpx;
  font-weight: 600;
  line-height: 28rpx;
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
.value {
  color: var(--color-primary);
  font-size: 24rpx;
  font-weight: 700;
  line-height: 30rpx;
  flex-shrink: 0;
}
</style>
