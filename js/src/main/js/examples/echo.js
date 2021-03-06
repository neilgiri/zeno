let client_info = {
  props: ['node'],

  data: function() {
    return {
      message: "",
    };
  },

  methods: {
    echo: function() {
      if (this.message === "") {
        return;
      }
      this.node.actor.echo(this.message);
      this.message = "";
    }
  },

  template: `
    <div>
      <div>Number of messages received: {{node.actor.numMessagesReceived}}</div>
      <button v-on:click="echo">Echo</button>
      <input v-model="message" v-on:keyup.enter="echo"></input>
    </div>
  `,
};

let server_info = {
  props: ['node'],

  template: `
    <div>
      <div>Number of messages received: {{node.actor.numMessagesReceived}}</div>
    </div>
  `,
};

function make_nodes(Echo, snap) {
  // Create the nodes.
  let nodes = {};
  nodes[Echo.server.address] = {
    actor: Echo.server,
    svg: snap.circle(150, 50, 20).attr(
        {fill: '#e74c3c', stroke: 'black', 'stroke-width': '3pt'}),
  };
  nodes[Echo.clientA.address] = {
    actor: Echo.clientA,
    svg: snap.circle(75, 150, 20).attr(
        {fill: '#3498db', stroke: 'black', 'stroke-width': '3pt'}),
  };
  nodes[Echo.clientB.address] = {
    actor: Echo.clientB,
    svg: snap.circle(225, 150, 20).attr(
        {fill: '#2ecc71', stroke: 'black', 'stroke-width': '3pt'}),
  };

  // Add node titles.
  snap.text(150, 20, 'Server').attr({'text-anchor': 'middle'});
  snap.text(75, 190, 'Client A').attr({'text-anchor': 'middle'});
  snap.text(225, 190, 'Client B').attr({'text-anchor': 'middle'});

  return nodes;
}

function make_app(Echo, snap, app_id) {
  let nodes = make_nodes(Echo, snap)

  // Create the vue app.
  let vue_app = new Vue({
    el: app_id,

    data: {
      node: nodes[Echo.server.address],
      transport: Echo.transport,
      send_message: (message, callback) => {
        let src = nodes[message.src];
        let dst = nodes[message.dst];
        let svg_message =
          snap.circle(src.svg.attr("cx"), src.svg.attr("cy"), 9)
              .attr({fill: '#2c3e50'});
        snap.prepend(svg_message);
        svg_message.animate(
          {cx: dst.svg.attr("cx"), cy: dst.svg.attr("cy")},
          250 + Math.random() * 200,
          callback);
      }
    },

    computed: {
      current_component: function() {
        if (this.node.actor.address.address.includes("Server")) {
          return server_info;
        } else if (this.node.actor.address.address.includes("Client")) {
          return client_info;
        } else {
          // Impossible!
        }
      },
    },
  });

  // Select a node by clicking it.
  for (let node of Object.values(nodes)) {
    node.svg.node.onclick = () => {
      vue_app.node = node;
    }
  }
}

function main() {
  make_app(zeno.examples.js.SimulatedEcho.Echo,
           Snap('#simulated_animation'),
           '#simulated_app');

  make_app(zeno.examples.js.ClickthroughEcho.Echo,
           Snap('#clickthrough_animation'),
           '#clickthrough_app');
}

window.onload = main
