// The menu's "Features" item: marked for the stylesheet, with sparks of different sizes born just outside its text now
// and then, flying on away from it and fading. Set up again after each page the theme loads in place.
(function () {
  var MAX_SPARKS = 6;
  var EVERY_MS = 450;
  var timer = null;
  var calm = window.matchMedia("(prefers-reduced-motion: reduce)");

  function spark(link) {
    if (link.querySelectorAll(".sb-spark").length >= MAX_SPARKS) {
      return;
    }
    var text = link.querySelector(".md-ellipsis") || link;
    var em = parseFloat(getComputedStyle(link).fontSize) || 16;
    // The letters' own box (their block spans the whole menu), within the link: a spark is born on a ring just outside
    // it and flies on a little away from it.
    var range = document.createRange();
    range.selectNodeContents(text);
    var letters = range.getBoundingClientRect();
    var box = link.getBoundingClientRect();
    var cx = letters.left - box.left + letters.width / 2;
    var cy = letters.top - box.top + letters.height / 2;
    // A clear gap between the letters and the spark's own edge, so a big spark is born further out than a small one.
    var size = 0.35 + Math.random() * 0.3;
    var gap = (0.05 + size * 0.35) * em;
    var rx = letters.width / 2 + gap;
    var ry = letters.height / 2 + gap;
    var angle = Math.random() * Math.PI * 2;
    var away = (0.4 + Math.random() * 0.3) * em;
    var s = document.createElement("span");
    s.className = "sb-spark";
    s.textContent = "✦";
    s.style.left = cx + Math.cos(angle) * rx + "px";
    s.style.top = cy + Math.sin(angle) * ry + "px";
    s.style.fontSize = size + "em";
    // Away from the text, drifting up a little as sparks from a fire do.
    s.style.setProperty("--sb-dx", (Math.cos(angle) * away).toFixed(1) + "px");
    s.style.setProperty("--sb-dy", (Math.sin(angle) * away - 0.2 * em).toFixed(1) + "px");
    s.style.animationDuration = (1.1 + Math.random() * 0.9).toFixed(2) + "s";
    s.addEventListener("animationend", function () {
      s.remove();
    });
    link.appendChild(s);
  }

  document$.subscribe(function () {
    var links = [];
    document.querySelectorAll(".md-nav__link, .md-tabs__link").forEach(function (link) {
      if (link.textContent.trim() === "Features") {
        link.classList.add("sb-features");
        links.push(link);
      }
    });
    if (timer) {
      clearInterval(timer);
      timer = null;
    }
    if (!calm.matches && links.length) {
      timer = setInterval(function () {
        links.forEach(function (link) {
          if (link.offsetParent !== null) {
            spark(link);
          }
        });
      }, EVERY_MS);
    }
  });
})();
