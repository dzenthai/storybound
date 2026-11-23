// Marks the menu's "Features" item, so the stylesheet can make it stand out; again after each page the theme loads in place.
document$.subscribe(function () {
  document.querySelectorAll(".md-nav__link, .md-tabs__link").forEach(function (link) {
    if (link.textContent.trim() === "Features") {
      link.classList.add("sb-features");
    }
  });
});
