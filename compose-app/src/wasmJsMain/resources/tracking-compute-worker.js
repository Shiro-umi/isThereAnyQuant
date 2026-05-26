self.onmessage = function (event) {
  var data = event.data || {};
  var requestId = data.requestId;

  try {
    if (data.taskType !== "BuildTrackingEdgeLayout") {
      throw new Error("Unsupported task type: " + data.taskType);
    }

    var tradeDates = data.tradeDates || [];
    var edges = data.edges || [];
    var dayIndexByDate = Object.create(null);

    for (var i = 0; i < tradeDates.length; i += 1) {
      dayIndexByDate[tradeDates[i]] = i;
    }

    var indexedEdges = [];
    for (var j = 0; j < edges.length; j += 1) {
      var edge = edges[j];
      var fromIndex = dayIndexByDate[edge.fromDate];
      var toIndex = dayIndexByDate[edge.toDate];

      if (fromIndex === undefined || toIndex === undefined) {
        continue;
      }

      indexedEdges.push({
        kind: edge.kind,
        fromSection: edge.fromSection,
        fromSlotIndex: edge.fromSlotIndex,
        toSection: edge.toSection,
        toSlotIndex: edge.toSlotIndex,
        fromIndex: fromIndex,
        toIndex: toIndex
      });
    }

    self.postMessage({
      requestId: requestId,
      success: true,
      indexedEdges: indexedEdges
    });
  } catch (error) {
    self.postMessage({
      requestId: requestId,
      success: false,
      error: error && error.message ? error.message : String(error)
    });
  }
};
