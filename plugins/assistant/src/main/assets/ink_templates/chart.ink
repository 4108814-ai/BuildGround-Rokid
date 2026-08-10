<script type="application/json" def>
{
  "data": {
    "title": "Chart",
    "chartType": "line",
    "chartSeries": [
      { "yName": "value0", "label": "Current" },
      { "yName": "value1", "label": "Reference" }
    ],
    "chartPoints": [
      { "label": "A", "value0": 12, "value1": 10 },
      { "label": "B", "value0": 18, "value1": 14 },
      { "label": "C", "value0": 15, "value1": 16 }
    ],
    "legend": [
      { "label": "Current" },
      { "label": "Reference" }
    ],
    "caption": ""
  }
}
</script>

<page>
  <scroll-view class="page" scroll-y="true">
    <view class="header">
      <text class="title">{{ title }}</text>
      <text class="caption" wx:if="{{ caption }}">{{ caption }}</text>
    </view>
    <view class="chart-frame">
      <chart class="chart" type="{{ chartType }}" series="{{ chartSeries }}" data="{{ chartPoints }}" animate="true" />
    </view>
    <view class="legend" wx:if="{{ legend }}">
      <view class="legend-item" wx:for="{{ legend }}">
        <text class="legend-label">{{ item.label }}</text>
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
.header {
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  gap: 12rpx;
}
.title {
  color: var(--color-primary);
  font-size: 34rpx;
  font-weight: 700;
  line-height: 40rpx;
}
.caption {
  color: var(--color-text-secondary);
  font-size: 18rpx;
  line-height: 24rpx;
  text-align: right;
  opacity: 0.68;
}
.chart-frame {
  display: flex;
  flex-direction: column;
  width: 100%;
  box-sizing: border-box;
  padding: 8rpx;
  background-color: var(--color-background);
  border-width: 1rpx;
  border-style: solid;
  border-color: var(--border-color-muted);
  border-radius: var(--radius-sm, 18rpx);
}
.chart {
  width: 100%;
  height: 190rpx;
}
.legend {
  display: flex;
  flex-direction: row;
  flex-wrap: wrap;
  gap: 8rpx;
}
.legend-item {
  display: flex;
  flex-direction: row;
  align-items: center;
  padding: 4rpx 10rpx;
  border-width: 1rpx;
  border-style: solid;
  border-color: var(--border-color-muted);
  border-radius: var(--radius-sm, 18rpx);
}
.legend-label {
  color: var(--color-text-secondary);
  font-size: 18rpx;
  line-height: 24rpx;
  opacity: 0.72;
}
</style>
