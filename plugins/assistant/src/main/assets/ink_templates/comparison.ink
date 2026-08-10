<script type="application/json" def>
{
  "data": {
    "title": "Comparison",
    "left": {
      "label": "Option A",
      "items": [
        { "label": "Cost", "value": "$24" },
        { "label": "Time", "value": "2 days" }
      ]
    },
    "right": {
      "label": "Option B",
      "items": [
        { "label": "Cost", "value": "$31" },
        { "label": "Time", "value": "1 day" }
      ]
    },
    "verdict": ""
  }
}
</script>

<page>
  <view class="page">
    <text class="title">{{ title }}</text>
    <view class="columns">
      <view class="column">
        <text class="column-label">{{ left.label }}</text>
        <view class="items">
          <view class="item" wx:for="{{ left.items }}">
            <text class="item-label">{{ item.label }}</text>
            <text class="item-value">{{ item.value }}</text>
          </view>
        </view>
      </view>
      <view class="column">
        <text class="column-label">{{ right.label }}</text>
        <view class="items">
          <view class="item" wx:for="{{ right.items }}">
            <text class="item-label">{{ item.label }}</text>
            <text class="item-value">{{ item.value }}</text>
          </view>
        </view>
      </view>
    </view>
    <view class="verdict" wx:if="{{ verdict }}">
      <text class="verdict-text">{{ verdict }}</text>
    </view>
  </view>
</page>

<style>
.page {
  display: flex;
  flex-direction: column;
  gap: 14rpx;
  width: 100%;
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
.columns {
  display: flex;
  flex-direction: row;
  gap: 10rpx;
}
.column {
  display: flex;
  flex-direction: column;
  gap: 10rpx;
  flex-grow: 1;
  flex-basis: 0%;
  min-width: 0rpx;
  box-sizing: border-box;
  padding: 12rpx;
  background-color: var(--color-background);
  border-width: 2rpx;
  border-style: solid;
  border-color: var(--border-color-default);
  border-radius: var(--radius-md, 18rpx);
}
.column-label {
  color: var(--color-primary);
  font-size: 24rpx;
  font-weight: 700;
  line-height: 30rpx;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.items {
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}
.item {
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  gap: 8rpx;
  padding: 7rpx 8rpx;
  border-width: 1rpx;
  border-style: solid;
  border-color: var(--border-color-muted);
  border-radius: var(--radius-sm, 18rpx);
}
.item-label {
  color: var(--color-text-secondary);
  font-size: 18rpx;
  line-height: 24rpx;
  opacity: 0.7;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.item-value {
  color: var(--color-text-primary);
  font-size: 20rpx;
  font-weight: 600;
  line-height: 26rpx;
  text-align: right;
  flex-shrink: 0;
}
.verdict {
  display: flex;
  flex-direction: row;
  padding: 10rpx 12rpx;
  border-width: 1rpx;
  border-style: solid;
  border-color: var(--border-color-accent);
  border-radius: var(--radius-sm, 18rpx);
}
.verdict-text {
  color: var(--color-text-primary);
  font-size: 19rpx;
  font-weight: 600;
  line-height: 26rpx;
}
</style>
