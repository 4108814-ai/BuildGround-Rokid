<script type="application/json" def>
{
  "data": {
    "title": "Weather",
    "location": "",
    "temperature": "18°",
    "condition": "Clear",
    "high": "",
    "low": "",
    "precipitation": "",
    "humidity": "",
    "wind": "",
    "hourly": [],
    "forecast": []
  }
}
</script>

<page>
  <view class="page">
    <view class="header">
      <text class="title">{{ title }}</text>
      <text class="location" wx:if="{{ location }}">{{ location }}</text>
    </view>
    <view class="current">
      <text class="temperature">{{ temperature }}</text>
      <text class="condition">{{ condition }}</text>
    </view>
    <view class="facts" wx:if="{{ high || low || precipitation || humidity || wind }}">
      <text class="fact" wx:if="{{ high }}">H {{ high }}</text>
      <text class="fact" wx:if="{{ low }}">L {{ low }}</text>
      <text class="fact" wx:if="{{ precipitation }}">{{ precipitation }}</text>
      <text class="fact" wx:if="{{ humidity }}">{{ humidity }}</text>
      <text class="fact" wx:if="{{ wind }}">{{ wind }}</text>
    </view>
    <chart
      wx:if="{{ hourly[1] }}"
      class="curve"
      type="area"
      series="temp"
      data="{{ hourly }}"
      smooth="true"
      animate="true"
      show-value-labels="true" />
    <view class="forecast" wx:if="{{ forecast[0] }}">
      <view class="forecast-cell" wx:for="{{ forecast }}">
        <text class="forecast-label">{{ item.label }}</text>
        <text class="forecast-temperature">{{ item.temperature }}</text>
        <text class="forecast-condition" wx:if="{{ item.condition }}">{{ item.condition }}</text>
      </view>
    </view>
  </view>
</page>

<style>
.page {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
  width: 100%;
  box-sizing: border-box;
  padding: 18rpx;
  color: var(--color-text-primary);
  background-color: var(--color-background);
  border-width: 2rpx;
  border-style: solid;
  border-color: var(--border-color-default);
  border-radius: var(--radius-md, 18rpx);
}
.header {
  display: flex;
  flex-direction: row;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12rpx;
}
.title {
  color: var(--color-primary);
  font-size: 30rpx;
  font-weight: 700;
  line-height: 38rpx;
  flex-shrink: 1;
  min-width: 0rpx;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.location {
  color: var(--color-text-secondary);
  font-size: 21rpx;
  line-height: 27rpx;
  opacity: 0.72;
  flex-shrink: 0;
  white-space: nowrap;
}
.current {
  display: flex;
  flex-direction: row;
  align-items: baseline;
  gap: 16rpx;
}
/* The temperature never wraps: it shrinks the condition instead, and the
   condition ellipsizes, so a long forecast word can never break mid-syllable. */
.temperature {
  color: var(--color-primary);
  font-size: 58rpx;
  font-weight: 700;
  line-height: 64rpx;
  flex-shrink: 0;
  white-space: nowrap;
}
/* One gap value: the WXSS subset parses a single length, and a two-value
   row/column gap silently resolves to none, gluing the facts together. */
.facts {
  display: flex;
  flex-direction: row;
  flex-wrap: wrap;
  gap: 18rpx;
}
.condition {
  color: var(--color-text-primary);
  font-size: 26rpx;
  font-weight: 600;
  line-height: 32rpx;
  flex-shrink: 1;
  min-width: 0rpx;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.fact {
  color: var(--color-text-secondary);
  font-size: 19rpx;
  line-height: 25rpx;
  opacity: 0.72;
  white-space: nowrap;
}
.curve {
  width: 100%;
  height: 190rpx;
}
.forecast {
  display: flex;
  flex-direction: row;
  gap: 8rpx;
}
.forecast-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4rpx;
  flex-grow: 1;
  flex-basis: 0%;
  min-width: 0rpx;
  padding: 10rpx 6rpx;
  background-color: var(--color-background);
  border-width: 1rpx;
  border-style: solid;
  border-color: var(--border-color-muted);
  border-radius: var(--radius-sm, 18rpx);
}
.forecast-label {
  color: var(--color-text-secondary);
  font-size: 18rpx;
  line-height: 22rpx;
  opacity: 0.72;
}
.forecast-temperature {
  color: var(--color-text-primary);
  font-size: 26rpx;
  font-weight: 700;
  line-height: 30rpx;
}
.forecast-condition {
  color: var(--color-text-secondary);
  font-size: 16rpx;
  line-height: 20rpx;
  opacity: 0.6;
}
</style>
