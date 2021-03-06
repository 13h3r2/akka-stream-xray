newGraph = () ->
  nodeIdToIndexMap = {}
  _nodeIndex = 0
  nodeIndex = (nodeId) ->
    if nodeIdToIndexMap[nodeId]?
      nodeIdToIndexMap[nodeId]
    else
      idx = _nodeIndex
      _nodeIndex += 1
      nodeIdToIndexMap[nodeId] = idx

  # Create the input graph
  g = new dagreD3.graphlib.Graph().setGraph({}).setDefaultEdgeLabel(->
    {}
  )

  # Create the renderer
  render = new dagreD3.render()

  # Set up an SVG group so that we can translate the final graph.
  svg = d3.select("svg")
  svgGroup = svg.select("g")

  doRender = () ->
    g.nodes().forEach (v) ->
      node = g.node(v)

      # Round the corners of the nodes
      node.rx = node.ry = 5
      return

    # render d3.select("svg g"), g
    svgGroup.call(render, g)


    # Center the graph
    xCenterOffset = (svg.attr("width") - g.graph().width) / 2
    svgGroup.attr "transform", "translate(" + xCenterOffset + ", 20)"
    svg.attr "height", g.graph().height + 40

  nodeHtml = (node) ->
    html = "<div>";
    html += "<span class=node-name>#{node.name}</span>";
    html += "<span class=message-count>#{stats.counts[node.id] || 0 }</span>";
    html += "<span class=message-rate>#{(stats.rates[node.id] || 0).toFixed(1)} / s</span>";
    html += "</div>";
    html

  nodes = {}
  registerNode = ({id, name}) ->
    console.log("graph.new-node", id)
    nodes[id] = {id, name, messageCount: 0, deliveries: []}
    nodes[id].deliveries.name = name
    g.setNode nodeIndex(id), id: id, name: name, labelType: "html", label: nodeHtml(nodes[id]), class: "type-#{id}"


  registerEdge = ({from, to, properties}) ->
    console.log("graph.new-edge", from, to, properties)
    g.setEdge nodeIndex(from), nodeIndex(to),
      label: properties.label
      class: "jerk"
      style: if properties.subStream then "stroke: #f66; stroke-width: 2px" else null

  toggleStates = {}
  stats = {counts: {}, rates: {}, maxRate: 1}

  calcColor = (percent) ->
    shadeR = Math.round(255 - (percent * 255))
    shadeG = Math.round(255 - (percent * 32)) # 255 - no activity, 128 - full activity
    shadeB = Math.round(255 - (percent * 255))
    "RGB(#{shadeR}, #{shadeG}, #{shadeB})"

  new machina.Fsm(
    initialState: "init"
    states:
      init:
        "graph.new-node": registerNode
        "graph.new-edge": registerEdge
        "graph.initialized": ->
          @transition("drawing")
        "*": (payload, a) ->
          console.log("unknown msg:", payload, a)
      drawing:
        _onEnter: ->
          try
            doRender()
          catch error
            console.log(error)
            console.log(error.stack)
        "graph.new-node": (data) ->
          registerNode(data)
          doRender()
        "graph.new-edge": (data) ->
          registerEdge(data)
          doRender()
        "update-stats": (_stats) ->
          stats = _stats
        "render": ->
          g.nodes().forEach (idx) ->
            gNode = g.node(idx)
            node = nodes[gNode.id]
            rate = stats.rates[node.id]
            gNode.label = nodeHtml(node)
            gNode.style = "fill: #{calcColor(rate / stats.maxRate)}"
          svgGroup.call(render, g)
        "*": (payload, a) ->
          console.log("unknown msg:", payload, a)
  )

graph = newGraph()
counter = newCounter()

socket = websocketFsm()

# socket.handle("publish", ["ping"])
# socket.handle("publish", ["ping"])

socket.on "graph.new-node", (n) -> graph.handle "graph.new-node", n
socket.on "graph.new-edge", (n) ->
  graph.handle "graph.new-edge", n
socket.on "graph.initialized", (n) -> graph.handle "graph.initialized", n
socket.on "node.message", (n) -> counter.handle "node.message", n
counter.on "stats", (stats) -> graph.handle "update-stats", stats

i = setInterval(
  (->
    try
      counter.handle("calculate")
      graph.handle("render")
    catch e
      console.log e.stack
  ),
  200)

stop = () ->
  clearInterval(i)
  socket.handle("close")
