(function () {
  'use strict';

  var base = 'assets/art/';
  var files = {
    heroPlanet: 'hero-planet.webp',
    emptyMoon: 'empty-moon.webp',
    emptyPlanetBlue: 'empty-planet-blue.webp',
    emptyPlanetViolet: 'empty-planet-violet.webp',
    brandMoon: 'brand-moon.webp',
    starGold: 'star-gold.webp',
    starCream: 'star-cream.webp',
    textureRose: 'texture-rose.webp',
    profileOrb: 'profile-orb.webp',
    nebula: 'nebula.webp'
  };
  var art = {};
  Object.keys(files).forEach(function (key) { art[key] = base + files[key]; });

  window.PIQ = window.PIQ || {};
  window.PIQ.art = art;
})();
