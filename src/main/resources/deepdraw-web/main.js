/**
 * The webview's whole job: mount DeepDraw, tell the plugin when something
 * changed, and hand over the document when it is asked for.
 *
 * The library is loaded as its own <script> before this one and attaches
 * `window.DeepDraw`, so nothing here bundles it.
 *
 * **This side cannot speak first.** `window.__deepdrawPost` is built from a
 * JCEF query that only exists on the plugin's side, so the plugin writes it
 * onto `window` when the page has loaded and then calls `__deepdrawBoot`. Until
 * that call there is nobody listening, which is why `ready` is sent from there
 * rather than from the end of this file.
 */
(function () {
  'use strict';

  var app = null;

  /** Plugin → page. Called with the message as a JSON string. */
  window.__deepdrawHostMessage = function (payload) {
    var message;
    try {
      message = JSON.parse(payload);
    } catch (e) {
      return;
    }
    handle(message);
  };

  /** The plugin's entry point, called once the bridge is in place. */
  window.__deepdrawBoot = function () {
    post({ type: 'ready' });
  };

  function post(message) {
    if (window.__deepdrawPost) window.__deepdrawPost(JSON.stringify(message));
  }

  window.addEventListener('error', function (event) {
    post({ type: 'error', message: String(event.message) });
  });

  function handle(message) {
    switch (message.type) {
      case 'init':
        mount(message.json, message.iconifyApi, message.theme);
        return;
      case 'load':
        if (app) app.loadDocument(DeepDraw.parseDocument(message.json));
        return;
      case 'getContent':
        if (app) post({ type: 'content', id: message.id, json: app.toJSON() });
        else fail(message.id, 'the drawing is not open yet');
        return;
      case 'render':
        render(message.id);
        return;
      case 'navigate':
        goTo(message.nodeId);
        return;
      case 'theme':
        if (app) app.setTheme(message.theme === 'dark' ? 'dark' : 'light');
        return;
    }
  }

  function mount(json, iconifyApi, theme) {
    var element = document.getElementById('app');
    if (!element) return;
    if (app) app.destroy();
    app = DeepDraw.mount(element, {
      document: DeepDraw.parseDocument(json),
      mode: 'edit',
      theme: theme === 'dark' ? 'dark' : 'light',
      // The IDE is the chrome here: the file has a name in a tab, saving is
      // Ctrl+S, and exporting is an action. The library's own top bar exists
      // for a standalone file opened off a disk, and its Export items write
      // through a download link, which has nowhere to land in an editor.
      hideTopBar: true,
      // The IDE has a tree, in the place its readers look for one, and it is
      // contributed by this plugin. A second one inside the canvas would
      // compete with it and cost the drawing the width.
      hideTree: true,
      iconifyApi: iconifyApi || undefined,
      // Renaming the drawing writes onto the document rather than through the
      // action bus, so it is reported separately or a rename would never mark
      // the file dirty.
      onTitleChange: function () {
        post({ type: 'edit' });
      },
    });

    app.onAction(function () {
      post({ type: 'edit' });
    });

    // The view has to follow the drawing, the reader's place in it and what
    // they have selected — all three of which the panes here already listen for.
    ['doc', 'current', 'selection'].forEach(function (event) {
      app.ctx.on(event, sendTree);
    });
    sendTree();
  }

  function render(id) {
    if (!app) return fail(id, 'the drawing is not open yet');
    // The drawing on the canvas, not the root: somebody three levels deep who
    // asks for a picture means the one they are looking at.
    DeepDraw.inlineImages(app.document)
      .then(function (doc) {
        var drawingId = doc.nodes[app.ctx.currentId] ? app.ctx.currentId : doc.rootId;
        if (!DeepDraw.childrenOf(doc, drawingId).length) {
          post({ type: 'rendered', id: id, base64: null });
          return null;
        }
        return DeepDraw.toImageBlob(doc, drawingId);
      })
      .then(function (blob) {
        if (!blob) return;
        return blob.arrayBuffer().then(function (buffer) {
          var bytes = new Uint8Array(buffer);
          var binary = '';
          for (var i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
          post({ type: 'rendered', id: id, base64: btoa(binary) });
        });
      })
      .catch(function (error) {
        fail(id, error && error.message ? error.message : String(error));
      });
  }

  function fail(id, message) {
    post({ type: 'failed', id: id, message: message });
  }

  // --- the hierarchy, for the tree the IDE draws ----------------------------

  var treePending = null;

  /** Coalesced: a drag fires an action per frame and the tree is unchanged by most. */
  function sendTree() {
    clearTimeout(treePending);
    treePending = setTimeout(function () {
      if (app) post({ type: 'tree', tree: snapshot(app) });
    }, 120);
  }

  function snapshot(current) {
    var doc = current.document;
    var selected = {};
    current.ctx.selection.forEach(function (id) {
      selected[id] = true;
    });
    var nodes = [];

    // `seen` is the path, not every node visited: a link may point at an
    // ancestor, and a hierarchy that walks into one never comes back.
    function walk(node, seen) {
      var children = drawingOf(doc, node);
      nodes.push({
        id: node.id,
        parentId: node.parentId,
        label: DeepDraw.nodeLabel(doc, node),
        hasChildren: children.length > 0,
        isCurrent: node.id === current.ctx.currentId,
        isSelected: selected[node.id] === true,
      });
      children.forEach(function (child) {
        var target = DeepDraw.resolve(doc, child) || child;
        if (seen[target.id]) return;
        var next = Object.assign({}, seen);
        next[target.id] = true;
        walk(child, next);
      });
    }

    var root = doc.nodes[doc.rootId];
    var start = {};
    start[doc.rootId] = true;
    if (root) walk(root, start);
    return { rootId: doc.rootId, nodes: nodes };
  }

  /** What is inside a node: its children, minus the arrows between them. */
  function drawingOf(doc, node) {
    var target = DeepDraw.resolve(doc, node) || node;
    return DeepDraw.childrenOf(doc, target.id).filter(function (child) {
      var resolved = DeepDraw.resolve(doc, child) || child;
      // Arrows are relationships rather than places, which is the same cut the
      // library's own hierarchy makes.
      return resolved.type !== 'arrow';
    });
  }

  /**
   * A row was clicked: a node with a drawing inside it opens, and a leaf is
   * shown where it lives — the two things the pane this view replaces did.
   */
  function goTo(nodeId) {
    if (!app) return;
    var doc = app.document;
    var node = doc.nodes[nodeId];
    if (!node) return;
    if (drawingOf(doc, node).length) {
      app.navigateTo(nodeId);
      return;
    }
    var parent = node.parentId;
    if (!parent || parent === app.ctx.currentId) {
      app.ctx.setSelection([nodeId]);
      return;
    }
    // A selection has to be applied *on arrival*: every hop of the journey there
    // clears it. The library does that with a callback on its canvas, which a
    // host cannot reach, so the arrival is watched for instead.
    var giveUp = null;
    var stop = app.ctx.on('current', function () {
      if (!app || app.ctx.currentId !== parent) return;
      stop();
      clearTimeout(giveUp);
      app.ctx.setSelection([nodeId]);
    });
    giveUp = setTimeout(stop, 3000);
    app.navigateTo(parent);
  }
})();
