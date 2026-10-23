(function () {
  'use strict';

  var SVG_NS = 'http://www.w3.org/2000/svg';
  var VIEW_W = 340;
  var VIEW_H = 180;
  var LINE = { left: 32, right: 326, top: 22, bottom: 136 };
  var BARS = { left: 32, right: 326, top: 24, bottom: 136 };
  var X_LABEL_Y = 158;
  var REF_LABEL_BOTTOM = 146;

  /* 记录条（record strip）：点少或量程几乎为零时，用一条账本横线 + 记录点代替假面积图。 */
  var STRIP_MAX_POINTS = 4;
  var FLAT_RANGE = 2;
  var FLAT_DELTA = 0.05;
  var REFERENCE_DELTA = 0.5;
  var STRIP = { left: 32, right: 326, top: 52, bottom: 104, xLabelY: 140, noteY: 18 };
  var STRIP_VIEW_H = 150;
  var STRIP_GUIDE_OFFSET = 22;

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

  function meanOf(values) {
    return values.reduce(function (sum, value) { return sum + value; }, 0) / values.length;
  }

  function sameValue(a, b) {
    return Math.abs(a - b) <= FLAT_DELTA;
  }

  function allEqual(values) {
    return values.every(function (value) { return sameValue(value, values[0]); });
  }

  function differsFromAll(reference, values) {
    return values.every(function (value) {
      return Math.abs(value - reference) > REFERENCE_DELTA;
    });
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

  function directIndexes(points, allLimit) {
    var last = points.length - 1;
    var indexes = [];
    if (points.length <= allLimit) {
      for (var i = 0; i <= last; i += 1) indexes.push(i);
      return indexes;
    }
    var peak = 0;
    points.forEach(function (point, index) {
      if (point.value > points[peak].value) peak = index;
    });
    [0, last, peak].forEach(function (index) {
      if (indexes.indexOf(index) === -1) indexes.push(index);
    });
    return indexes;
  }

  function approxTextWidth(text, latin, cjk) {
    var width = 0;
    var value = String(text);
    for (var i = 0; i < value.length; i += 1) {
      width += value.charCodeAt(i) > 255 ? (cjk || 10) : (latin || 6.2);
    }
    return width;
  }

  function labelBox(x, y, width, anchor) {
    var left = x;
    if (anchor === 'end') left = x - width;
    else if (anchor === 'middle') left = x - width / 2;
    return { left: left, right: left + width, top: y - 9, bottom: y + 3 };
  }

  function overlaps(a, b) {
    return a.left < b.right + 1 && a.right + 1 > b.left && a.top < b.bottom + 1 && a.bottom + 1 > b.top;
  }

  function collides(box, boxes) {
    for (var i = 0; i < boxes.length; i += 1) {
      if (overlaps(box, boxes[i])) return true;
    }
    return false;
  }

  function pickRefLabel(candidates, boxes) {
    for (var i = 0; i < candidates.length; i += 1) {
      var candidate = candidates[i];
      var box = candidate.box;
      if (box.left < 4 || box.right > VIEW_W - 4) continue;
      if (box.top < 8 || box.bottom > REF_LABEL_BOTTOM) continue;
      if (!collides(box, boxes)) return candidate;
    }
    return null;
  }

  function annotation(kind, count) {
    return el('figcaption', {
      class: 'chart-annot',
      'aria-hidden': 'true'
    }, kind + ' · ' + count + ' · 本机');
  }

  function ariaFor(points, unit, prefix) {
    var values = points.map(function (point) { return point.value; });
    var min = Math.min.apply(null, values);
    var max = Math.max.apply(null, values);
    var mean = meanOf(values);
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
    [0.25, 0.5, 0.75].forEach(function (fraction) {
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

  function guides(svg, plot) {
    svg.appendChild(svgEl('line', {
      x1: String(plot.left),
      x2: String(plot.left),
      y1: String(plot.top),
      y2: String(plot.bottom),
      class: 'chart-guide'
    }));
    svg.appendChild(svgEl('line', {
      x1: String(plot.left),
      x2: String(plot.right),
      y1: String(plot.bottom),
      y2: String(plot.bottom),
      class: 'chart-baseline'
    }));
  }

  function layoutLineLabels(points, xOf, yOf) {
    var last = points.length - 1;
    var placed = [];
    directIndexes(points, 6).forEach(function (index) {
      var anchor = index === 0 ? 'start' : (index === last ? 'end' : 'middle');
      var text = fmt(points[index].value);
      var width = approxTextWidth(text, 6.1, 10);
      var x = xOf(index);
      var y = yOf(points[index].value) - 9;
      var box = labelBox(x, y, width, anchor);
      var tries = 0;
      while (tries < 2 && collides(box, placed.map(function (item) { return item.box; }))) {
        y -= 12;
        box = labelBox(x, y, width, anchor);
        tries += 1;
      }
      if (y < 8) return;
      if (collides(box, placed.map(function (item) { return item.box; }))) return;
      placed.push({ index: index, anchor: anchor, x: x, y: y, text: text, box: box });
    });
    return placed;
  }

  function layoutStripLabels(points, xOf, yOf) {
    var last = points.length - 1;
    var placed = [];
    points.forEach(function (point, index) {
      var anchor = index === 0 ? 'start' : (index === last ? 'end' : 'middle');
      var text = fmt(point.value);
      var width = approxTextWidth(text, 6.1, 10);
      var x = xOf(index);
      var valueY = yOf(point.value);
      var boxes = placed.map(function (item) { return item.box; });
      var y = valueY - 11;
      var box = labelBox(x, y, width, anchor);
      if (y < 32 || collides(box, boxes)) {
        y = valueY + 18;
        box = labelBox(x, y, width, anchor);
      }
      if (collides(box, boxes)) return;
      placed.push({ index: index, anchor: anchor, x: x, y: y, text: text, box: box });
    });
    return placed;
  }

  function layoutBarLabels(points, plot, slot, yOf, zeroY) {
    var placed = [];
    directIndexes(points, 12).forEach(function (index) {
      var point = points[index];
      var x = plot.left + slot * (index + 0.5);
      var valueY = yOf(point.value);
      var barY = Math.min(valueY, zeroY);
      var barBottom = Math.max(valueY, zeroY);
      var text = fmt(point.value);
      var width = approxTextWidth(text, 6.1, 10);
      var y = point.value < 0 ? barBottom + 12 : barY - 5;
      if (y < 8) y = barY + 12;
      var box = labelBox(x, y, width, 'middle');
      if (collides(box, placed.map(function (item) { return item.box; }))) {
        y -= 11;
        box = labelBox(x, y, width, 'middle');
      }
      if (y < 8) return;
      if (collides(box, placed.map(function (item) { return item.box; }))) return;
      placed.push({ index: index, anchor: 'middle', x: x, y: y, text: text, box: box });
    });
    return placed;
  }

  function lineStripSvg(points, values, unit) {
    var flat = allEqual(values);
    var mean = meanOf(values);
    var plot = STRIP;
    var step = (plot.right - plot.left) / (points.length - 1);
    var xOf = function (index) { return plot.left + step * index; };
    var yOf;
    if (flat) {
      var centerY = (plot.top + plot.bottom) / 2;
      yOf = function () { return centerY; };
    } else {
      var low = Math.min.apply(null, values);
      var high = Math.max.apply(null, values);
      var padding = Math.max(1, (high - low) * 0.35);
      var domainLow = low - padding;
      var domainHigh = high + padding;
      yOf = function (value) {
        return plot.top + (domainHigh - value) / (domainHigh - domainLow) * (plot.bottom - plot.top);
      };
    }
    var guideY = yOf(mean);

    var svg = svgEl('svg', {
      viewBox: '0 0 ' + VIEW_W + ' ' + STRIP_VIEW_H,
      class: 'chart-svg',
      'aria-hidden': 'true',
      focusable: 'false'
    });

    [guideY - STRIP_GUIDE_OFFSET, guideY + STRIP_GUIDE_OFFSET].forEach(function (y) {
      var lineY = Math.round(y * 100) / 100;
      if (lineY < 34 || lineY > STRIP_VIEW_H - 34) return;
      svg.appendChild(svgEl('line', {
        x1: String(plot.left),
        x2: String(plot.right),
        y1: String(lineY),
        y2: String(lineY),
        class: 'chart-grid'
      }));
    });

    svg.appendChild(svgEl('line', {
      x1: String(plot.left),
      x2: String(plot.right),
      y1: String(Math.round(guideY * 100) / 100),
      y2: String(Math.round(guideY * 100) / 100),
      class: 'chart-strip-rule'
    }));

    if (flat) {
      svg.appendChild(svgEl('text', {
        x: String(plot.left),
        y: String(plot.noteY),
        class: 'chart-strip-note',
        'text-anchor': 'start'
      }, points.length + ' 个完整周期 · 均值 ' + fmt(mean)));
    } else if (differsFromAll(mean, values)) {
      svg.appendChild(svgEl('text', {
        x: String(plot.right),
        y: String(plot.noteY),
        class: 'chart-ref-label',
        'text-anchor': 'end'
      }, '均值 ' + fmt(mean)));
    }

    points.forEach(function (point, index) {
      svg.appendChild(svgEl('circle', {
        cx: String(Math.round(xOf(index) * 100) / 100),
        cy: String(Math.round(yOf(point.value) * 100) / 100),
        r: '3.2',
        class: 'chart-dot chart-dot-strip',
        style: '--i:' + index
      }, svgEl('title', {}, pointTitle(point, unit))));
    });

    layoutStripLabels(points, xOf, yOf).forEach(function (label) {
      svg.appendChild(svgEl('text', {
        x: String(Math.round(label.x * 100) / 100),
        y: String(Math.round(label.y * 100) / 100),
        class: 'chart-value-label',
        'text-anchor': label.anchor
      }, label.text));
    });

    var indexes = points.length <= STRIP_MAX_POINTS
      ? points.map(function (point, index) { return index; })
      : labelIndexes(points.length);
    indexes.forEach(function (index) {
      var anchor = index === 0 ? 'start' : (index === points.length - 1 ? 'end' : 'middle');
      xLabel(svg, xOf(index), plot.xLabelY, points[index].label, anchor);
    });

    return svg;
  }

  function lineSeriesSvg(points, values, average, low, high, unit) {
    var plot = LINE;
    var step = (plot.right - plot.left) / (points.length - 1);
    var yOf = function (value) {
      return plot.top + (high - value) / (high - low) * (plot.bottom - plot.top);
    };
    var xOf = function (index) { return plot.left + step * index; };
    var showAverage = average !== null && differsFromAll(average, values);

    var svg = svgEl('svg', {
      viewBox: '0 0 ' + VIEW_W + ' ' + VIEW_H,
      class: 'chart-svg',
      'aria-hidden': 'true',
      focusable: 'false'
    });

    var pathData = points.map(function (point, index) {
      return (index === 0 ? 'M' : 'L') + Math.round(xOf(index) * 100) / 100 + ' ' +
        Math.round(yOf(point.value) * 100) / 100;
    }).join(' ');

    grid(svg, plot);
    guides(svg, plot);
    yAxisLabels(svg, plot, low, high);

    if (showAverage) {
      var averageY = yOf(average);
      svg.appendChild(svgEl('line', {
        x1: String(plot.left),
        x2: String(plot.right),
        y1: String(Math.round(averageY * 100) / 100),
        y2: String(Math.round(averageY * 100) / 100),
        class: 'chart-average'
      }, svgEl('title', {}, '均值 ' + fmt(average) + ' ' + unit)));
    }

    svg.appendChild(svgEl('path', {
      d: pathData,
      class: 'chart-line',
      pathLength: '1',
      fill: 'none'
    }));

    points.forEach(function (point, index) {
      var cx = String(Math.round(xOf(index) * 100) / 100);
      var cy = String(Math.round(yOf(point.value) * 100) / 100);
      svg.appendChild(svgEl('circle', {
        cx: cx,
        cy: cy,
        r: '2.6',
        class: 'chart-dot',
        style: '--i:' + index
      }, svgEl('title', {}, pointTitle(point, unit))));
    });

    var labels = layoutLineLabels(points, xOf, yOf);
    var labelBoxes = labels.map(function (item) { return item.box; });
    labels.forEach(function (label) {
      svg.appendChild(svgEl('text', {
        x: String(Math.round(label.x * 100) / 100),
        y: String(Math.round(label.y * 100) / 100),
        class: 'chart-value-label',
        'text-anchor': label.anchor
      }, label.text));
    });

    if (showAverage) {
      var averageText = '均值 ' + fmt(average);
      var averageWidth = approxTextWidth(averageText, 6.1, 9.6);
      var averageLabelY = Math.round(yOf(average) * 100) / 100;
      var averageCenter = (plot.left + plot.right) / 2;
      var averageCandidates = [
        { x: plot.right, y: averageLabelY - 5, anchor: 'end' },
        { x: plot.left + 3, y: averageLabelY - 5, anchor: 'start' },
        { x: averageCenter, y: averageLabelY - 5, anchor: 'middle' },
        { x: plot.right, y: averageLabelY + 12, anchor: 'end' },
        { x: plot.left + 3, y: averageLabelY + 12, anchor: 'start' },
        { x: averageCenter, y: averageLabelY + 12, anchor: 'middle' }
      ].map(function (candidate) {
        candidate.box = labelBox(candidate.x, candidate.y, averageWidth, candidate.anchor);
        return candidate;
      });
      var averageLabel = pickRefLabel(averageCandidates, labelBoxes);
      if (averageLabel) {
        svg.appendChild(svgEl('text', {
          x: String(Math.round(averageLabel.x * 100) / 100),
          y: String(Math.round(averageLabel.y * 100) / 100),
          class: 'chart-ref-label',
          'text-anchor': averageLabel.anchor
        }, averageText));
      }
    }

    labelIndexes(points.length).forEach(function (index) {
      var anchor = index === 0 ? 'start' : (index === points.length - 1 ? 'end' : 'middle');
      xLabel(svg, xOf(index), X_LABEL_Y, points[index].label, anchor);
    });

    return svg;
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
    var strip = points.length <= STRIP_MAX_POINTS || (high - low) < FLAT_RANGE;

    var figure = el('figure', {
      class: 'chart',
      role: 'img',
      'aria-label': options.ariaLabel || ariaFor(points, unit, '周期走势图')
    });

    figure.appendChild(strip
      ? lineStripSvg(points, values, unit)
      : lineSeriesSvg(points, values, average, low, high, unit));
    figure.appendChild(annotation('记录', points.length));
    return figure;
  }

  function bars(options) {
    options = options || {};
    var points = cleanPoints(options.points);
    if (points.length < 2) return empty(options);

    var unit = options.unit || '天';
    var values = points.map(function (point) { return point.value; });
    var reference = finite(options.reference);
    var referenceIsEveryValue = reference !== null && values.every(function (value) {
      return sameValue(value, reference);
    });
    var showReference = reference !== null && !referenceIsEveryValue;
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
    svg.appendChild(svgEl('line', {
      x1: String(plot.left),
      x2: String(plot.left),
      y1: String(plot.top),
      y2: String(plot.bottom),
      class: 'chart-guide'
    }));
    yAxisLabels(svg, plot, low, high);

    if (showReference) {
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
      class: 'chart-baseline'
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
        rx: '1',
        class: 'chart-bar',
        style: '--i:' + index
      }, svgEl('title', {}, pointTitle(point, unit))));
    });

    var labels = layoutBarLabels(points, plot, slot, yOf, zeroY);
    labels.forEach(function (label) {
      svg.appendChild(svgEl('text', {
        x: String(Math.round(label.x * 100) / 100),
        y: String(Math.round(label.y * 100) / 100),
        class: 'chart-value-label',
        'text-anchor': 'middle'
      }, label.text));
    });

    if (showReference) {
      var refText = '均值 ' + fmt(reference);
      var refLabelY = Math.round(yOf(reference) * 100) / 100;
      var refAnchorX = plot.left - 4;
      var refAxisWidth = approxTextWidth(fmt(reference), 6.1, 9.6);
      if (refLabelY > plot.top + 22 && refLabelY < plot.bottom - 26 && refAxisWidth <= 26) {
        svg.appendChild(svgEl('text', {
          x: String(refAnchorX),
          y: String(refLabelY - 2),
          class: 'chart-ref-label',
          'text-anchor': 'end'
        }, '均值'));
        svg.appendChild(svgEl('text', {
          x: String(refAnchorX),
          y: String(refLabelY + 11),
          class: 'chart-ref-label',
          'text-anchor': 'end'
        }, fmt(reference)));
      } else {
        var refWidth = approxTextWidth(refText, 6.1, 9.6);
        var refCenter = (plot.left + plot.right) / 2;
        var refCandidates = [
          { x: plot.right, y: refLabelY - 5, anchor: 'end' },
          { x: plot.left + 3, y: refLabelY - 5, anchor: 'start' },
          { x: refCenter, y: refLabelY - 5, anchor: 'middle' },
          { x: plot.right, y: refLabelY + 12, anchor: 'end' },
          { x: plot.left + 3, y: refLabelY + 12, anchor: 'start' },
          { x: refCenter, y: refLabelY + 12, anchor: 'middle' }
        ].map(function (candidate) {
          candidate.box = labelBox(candidate.x, candidate.y, refWidth, candidate.anchor);
          return candidate;
        });
        var refLabel = pickRefLabel(refCandidates, labels.map(function (item) { return item.box; }));
        if (refLabel) {
          svg.appendChild(svgEl('text', {
            x: String(Math.round(refLabel.x * 100) / 100),
            y: String(Math.round(refLabel.y * 100) / 100),
            class: 'chart-ref-label',
            'text-anchor': refLabel.anchor
          }, refText));
        }
      }
    }

    labelIndexes(points.length).forEach(function (index) {
      var anchor = index === 0 ? 'start' : (index === points.length - 1 ? 'end' : 'middle');
      xLabel(svg, plot.left + slot * (index + 0.5), X_LABEL_Y, points[index].label, anchor);
    });

    figure.appendChild(svg);
    figure.appendChild(annotation('样本', points.length));
    return figure;
  }

  window.PIQ = window.PIQ || {};
  window.PIQ.charts = { line: line, bars: bars };
})();
