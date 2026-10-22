(function () {
  'use strict';

  var SVG_NS = 'http://www.w3.org/2000/svg';
  var VIEW_W = 340;
  var VIEW_H = 180;
  var LINE = { left: 34, right: 300, top: 14, bottom: 148 };
  var BARS = { left: 34, right: 326, top: 14, bottom: 148 };

  function el(tag, attrs, children) {
    var node = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (key) {
        var value = attrs[key];
        if (value === null || value === undefined || value === false) return;
        if (key === 'text') node.textContent = value;
        else if (key === 'class') node.className = value;
        else node.setAttribute(key, value);
      });
    }
    append(node, children);
    return node;
  }

  function svgEl(tag, attrs, children) {
    var node = document.createElementNS(SVG_NS, tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (key) {
        var value = attrs[key];
        if (value === null || value === undefined || value === false) return;
        node.setAttribute(key, value);
      });
    }
    append(node, children);
    return node;
  }

  function append(node, children) {
    if (children === null || children === undefined || children === false) return;
    if (Array.isArray(children)) {
      children.forEach(function (item) { append(node, item); });
      return;
    }
    if (children.nodeType) node.appendChild(children);
    else node.appendChild(document.createTextNode(String(children)));
  }

  function finite(value) {
    if (value === null || value === undefined || value === '') return null;
    var number = Number(value);
    return Number.isFinite(number) ? number : null;
  }

  function fmt(value) {
    var number = finite(value);
    if (number === null) return '—';
    var rounded = Math.round(number * 10) / 10;
    return Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(1);
  }

  function cleanPoints(points) {
    return (Array.isArray(points) ? points : []).map(function (point) {
      return {
        label: String(point && point.label !== undefined ? point.label : ''),
        value: finite(point && point.value),
        title: String(point && point.title ? point.title : '')
      };
    }).filter(function (point) { return point.value !== null; });
  }

  function empty(options) {
    var text = (options && options.emptyText) || '数据不足';
    var label = (options && options.ariaLabel) || text;
    return el('div', { class: 'chart-empty', role: 'img', 'aria-label': label, text: text });
  }

  function labelIndexes(count) {
    var indexes = [0];
    if (count > 2) indexes.push(Math.floor((count - 1) / 2));
    indexes.push(count - 1);
    return indexes.filter(function (value, index, list) { return list.indexOf(value) === index; });
  }

  function ariaFor(points, unit, prefix) {
    var values = points.map(function (point) { return point.value; });
    var min = Math.min.apply(null, values);
    var max = Math.max.apply(null, values);
    var mean = values.reduce(function (sum, value) { return sum + value; }, 0) / values.length;
    return prefix + '：共 ' + points.length + ' 个点，最小 ' + fmt(min) + ' ' + unit +
      '，最大 ' + fmt(max) + ' ' + unit + '，均值 ' + fmt(mean) + ' ' + unit;
  }

  function pointTitle(point, unit) {
    if (point.title) return point.title;
    return (point.label || '') + ' · ' + fmt(point.value) + ' ' + unit;
  }

  function xLabel(svg, x, y, text, anchor) {
    svg.appendChild(svgEl('text', {
      x: String(Math.round(x * 100) / 100),
      y: String(y),
      class: 'chart-axis',
      'text-anchor': anchor
    }, text));
  }

  function yAxisLabels(svg, plot, low, high) {
    svg.appendChild(svgEl('text', {
      x: String(plot.left - 4),
      y: String(plot.top + 4),
      class: 'chart-axis',
      'text-anchor': 'end'
    }, fmt(high)));
    svg.appendChild(svgEl('text', {
      x: String(plot.left - 4),
      y: String(plot.bottom),
      class: 'chart-axis',
      'text-anchor': 'end'
    }, fmt(low)));
  }

  function grid(svg, plot) {
    [0, 1 / 3, 2 / 3, 1].forEach(function (fraction) {
      var y = plot.top + (plot.bottom - plot.top) * fraction;
      svg.appendChild(svgEl('line', {
        x1: String(plot.left),
        x2: String(plot.right),
        y1: String(Math.round(y * 100) / 100),
        y2: String(Math.round(y * 100) / 100),
        class: 'chart-grid'
      }));
    });
  }

  function line(options) {
    options = options || {};
    var points = cleanPoints(options.points);
    if (points.length < 2) return empty(options);

    var unit = options.unit || '天';
    var values = points.map(function (point) { return point.value; });
    var average = finite(options.average);
    var min = Math.min.apply(null, values);
    var max = Math.max.apply(null, values);
    var low = average === null ? min : Math.min(min, average);
    var high = average === null ? max : Math.max(max, average);
    if (high === low) { high = low + 1; }
    var plot = LINE;
    var step = (plot.right - plot.left) / (points.length - 1);
    var yOf = function (value) {
      return plot.top + (high - value) / (high - low) * (plot.bottom - plot.top);
    };
    var xOf = function (index) { return plot.left + step * index; };

    var figure = el('figure', {
      class: 'chart',
      role: 'img',
      'aria-label': options.ariaLabel || ariaFor(points, unit, '周期走势图')
    });
    var svg = svgEl('svg', {
      viewBox: '0 0 ' + VIEW_W + ' ' + VIEW_H,
      class: 'chart-svg',
      'aria-hidden': 'true',
      focusable: 'false'
    });
    grid(svg, plot);
    yAxisLabels(svg, plot, low, high);

    var pathData = points.map(function (point, index) {
      return (index === 0 ? 'M' : 'L') + Math.round(xOf(index) * 100) / 100 + ' ' +
        Math.round(yOf(point.value) * 100) / 100;
    }).join(' ');
    svg.appendChild(svgEl('path', {
      d: pathData,
      class: 'chart-line',
      pathLength: '1',
      fill: 'none'
    }));

    if (average !== null) {
      var averageY = yOf(average);
      svg.appendChild(svgEl('line', {
        x1: String(plot.left),
        x2: String(plot.right),
        y1: String(Math.round(averageY * 100) / 100),
        y2: String(Math.round(averageY * 100) / 100),
        class: 'chart-average'
      }, svgEl('title', {}, '均值 ' + fmt(average) + ' ' + unit)));
      svg.appendChild(svgEl('text', {
        x: String(plot.right + 4),
        y: String(Math.round(averageY * 100) / 100 - 4),
        class: 'chart-ref-label',
        'text-anchor': 'start'
      }, '均值'));
    }

    points.forEach(function (point, index) {
      svg.appendChild(svgEl('circle', {
        cx: String(Math.round(xOf(index) * 100) / 100),
        cy: String(Math.round(yOf(point.value) * 100) / 100),
        r: '3',
        class: 'chart-dot'
      }, svgEl('title', {}, pointTitle(point, unit))));
    });

    labelIndexes(points.length).forEach(function (index) {
      var anchor = index === 0 ? 'start' : (index === points.length - 1 ? 'end' : 'middle');
      xLabel(svg, xOf(index), VIEW_H - 8, points[index].label, anchor);
    });

    figure.appendChild(svg);
    return figure;
  }

  function bars(options) {
    options = options || {};
    var points = cleanPoints(options.points);
    if (points.length < 2) return empty(options);

    var unit = options.unit || '天';
    var values = points.map(function (point) { return point.value; });
    var reference = finite(options.reference);
    var low = Math.min(0, Math.min.apply(null, values));
    var high = Math.max(0, Math.max.apply(null, values));
    if (reference !== null) {
      low = Math.min(low, reference);
      high = Math.max(high, reference);
    }
    if (high === low) high = low + 1;
    var plot = BARS;
    var yOf = function (value) {
      return plot.top + (high - value) / (high - low) * (plot.bottom - plot.top);
    };
    var slot = (plot.right - plot.left) / points.length;
    var barWidth = Math.max(4, Math.min(18, slot * 0.58));
    var zeroY = yOf(0);

    var figure = el('figure', {
      class: 'chart',
      role: 'img',
      'aria-label': options.ariaLabel || ariaFor(points, unit, '误差柱状图')
    });
    var svg = svgEl('svg', {
      viewBox: '0 0 ' + VIEW_W + ' ' + VIEW_H,
      class: 'chart-svg',
      'aria-hidden': 'true',
      focusable: 'false'
    });
    grid(svg, plot);
    yAxisLabels(svg, plot, low, high);

    if (reference !== null) {
      var refY = yOf(reference);
      svg.appendChild(svgEl('line', {
        x1: String(plot.left),
        x2: String(plot.right),
        y1: String(Math.round(refY * 100) / 100),
        y2: String(Math.round(refY * 100) / 100),
        class: 'chart-ref'
      }, svgEl('title', {}, '平均误差 ' + fmt(reference) + ' ' + unit)));
    }

    svg.appendChild(svgEl('line', {
      x1: String(plot.left),
      x2: String(plot.right),
      y1: String(Math.round(zeroY * 100) / 100),
      y2: String(Math.round(zeroY * 100) / 100),
      class: 'chart-zero'
    }));

    points.forEach(function (point, index) {
      var center = plot.left + slot * (index + 0.5);
      var valueY = yOf(point.value);
      var barY = Math.min(valueY, zeroY);
      var height = Math.max(0, Math.abs(zeroY - valueY));
      svg.appendChild(svgEl('rect', {
        x: String(Math.round((center - barWidth / 2) * 100) / 100),
        y: String(Math.round(barY * 100) / 100),
        width: String(Math.round(barWidth * 100) / 100),
        height: String(Math.round(height * 100) / 100),
        rx: '3',
        class: 'chart-bar'
      }, svgEl('title', {}, pointTitle(point, unit))));
    });

    labelIndexes(points.length).forEach(function (index) {
      var anchor = index === 0 ? 'start' : (index === points.length - 1 ? 'end' : 'middle');
      xLabel(svg, plot.left + slot * (index + 0.5), VIEW_H - 8, points[index].label, anchor);
    });

    figure.appendChild(svg);
    return figure;
  }

  window.PIQ = window.PIQ || {};
  window.PIQ.charts = { line: line, bars: bars };
})();
