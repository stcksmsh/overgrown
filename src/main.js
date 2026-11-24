import "./style.css";

// Colours from the app's design spec (docs/DESIGN.md on main).
const INK = "#1B1B1B";
const WHITE = "#FFFFFF";

// The app's glyphs (app/src/main/res/drawable/glyph_*.xml), 48-unit grid.
const GLYPHS = {
  conifer: `<path d="M24 5L33 18H15Z M24 14L36 30H12Z M24 25L39 42H9Z"/><path class="st" d="M24 42V46"/>`,
  herb: `<path class="st" d="M24 46V24 M24 36L13 26 M24 34L35 24"/><circle cx="24" cy="17" r="7"/><circle cx="11" cy="21" r="6"/><circle cx="37" cy="19" r="6"/>`,
  leafy: `<path class="st" d="M24 46V20"/><ellipse cx="24" cy="12" rx="7" ry="10"/><ellipse cx="12" cy="26" rx="6" ry="9" transform="rotate(-50 12 26)"/><ellipse cx="36" cy="30" rx="6" ry="9" transform="rotate(50 36 30)"/>`,
  succulent: [-56, -28, 0, 28, 56].map((a) => `<ellipse cx="24" cy="24" rx="5.5" ry="14" transform="rotate(${a} 24 40)"/>`).join(""),
  fern: `<path class="st" d="M24 46V6 M24 40L10 34 M24 40L38 34 M24 33L11 27 M24 33L37 27 M24 26L13 20 M24 26L35 20 M24 19L16 13 M24 19L32 13 M24 13L19 7 M24 13L29 7"/>`,
};
const glyph = (name, color, size = 56) =>
  `<svg class="glyph" width="${size}" height="${size}" viewBox="0 0 48 48" aria-hidden="true" style="--c:${color}">${GLYPHS[name]}</svg>`;

// PotGauge: trapezoid pot with a rim, filled from the bottom to the share of water left.
let gaugeId = 0;
function gauge(pct, { w = 38, h = 44, line = INK, fill = INK, empty = "none" } = {}) {
  const id = `g${gaugeId++}`;
  const pot = `M${w * 0.08} ${h * 0.18}H${w * 0.92}L${w * 0.78} ${h * 0.96}H${w * 0.22}Z`;
  const top = h * 0.18, bottom = h * 0.96;
  const y = bottom - (bottom - top) * Math.max(0, Math.min(1, pct / 100));
  return `<svg width="${w}" height="${h}" viewBox="0 0 ${w} ${h}" role="img" aria-label="Water left: ${Math.round(pct)} percent">
    <defs><clipPath id="${id}"><path d="${pot}"/></clipPath></defs>
    <path d="${pot}" fill="${empty}"/>
    <rect x="0" y="${y}" width="${w}" height="${h}" fill="${fill}" clip-path="url(#${id})"/>
    <path d="${pot}" fill="none" stroke="${line}" stroke-width="2" stroke-linejoin="round"/>
    <path d="M${w * 0.02} ${top}H${w * 0.98}" stroke="${line}" stroke-width="3" stroke-linecap="round"/></svg>`;
}

const SNOW = `<svg width="24" height="24" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3V21 M4.2 7.5L19.8 16.5 M19.8 7.5L4.2 16.5" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" fill="none"/></svg>`;
const SUN = `<svg width="24" height="24" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="4.5" fill="currentColor"/><path d="M12 2V4.5 M12 19.5V22 M2 12H4.5 M19.5 12H22 M4.9 4.9L6.6 6.6 M17.4 17.4L19.1 19.1 M4.9 19.1L6.6 17.4 M17.4 6.6L19.1 4.9" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>`;
const WIND = `<svg width="24" height="24" viewBox="0 0 24 24" aria-hidden="true"><path d="M3 8H14a3 3 0 1 0-3-3 M3 12H19a3 3 0 1 1-3 3 M3 16H10" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" fill="none"/></svg>`;

const DAY = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
const today = new Date();
const dayName = (i) => DAY[(today.getDay() + i) % 7];

// ---- Hero: the Today mockup ----
document.getElementById("today-mock").innerHTML = `
  <div class="m-head"><span class="m-title">Today</span><span class="m-meta">${dayName(0)} ${today.getDate()} ${today.toLocaleString("en", { month: "short" })}<br/>Belgrade 6°</span></div>
  <div class="m-card brick m-alert">${SNOW}<span>Cold nights ${dayName(5)}–${dayName(7)}. Move Boris against the wall.</span><span class="m-btn light">Done</span></div>
  <div class="m-card sky">
    <div class="m-row"><div><div class="m-name">Pothos</div><div>Water about 700 ml</div></div>${glyph("leafy", INK, 52)}</div>
    <div class="m-row m-act">${gauge(8, { w: 34, h: 40, empty: WHITE })}<span class="m-sub">Nearly empty</span><span class="m-btn dark">Watered</span></div>
  </div>
  <div class="m-card marigold">
    <div class="m-row"><div><div class="m-name">Snake plant</div><div>How does the soil feel?</div></div>${glyph("succulent", INK, 52)}</div>
    <div class="m-row m-three"><span class="m-btn light">Wet</span><span class="m-btn light">OK</span><span class="m-btn light">Dry</span></div>
  </div>
  <div class="m-tiles">
    <div class="m-card surface m-tile"><div class="m-row"><span class="chip">${glyph("conifer", WHITE, 30)}</span>${gauge(70, { w: 28, h: 34, line: "var(--ink)", fill: "var(--gauge)" })}</div><b>Boris</b><span class="m-sub">Fine. Check ${dayName(2)}.</span></div>
    <div class="m-card surface m-tile"><div class="m-row"><span class="chip">${glyph("herb", WHITE, 30)}</span>${gauge(55, { w: 28, h: 34, line: "var(--ink)", fill: "var(--gauge)" })}</div><b>Basil</b><span class="m-sub">Fine. Water in 3 days.</span></div>
  </div>`;

// ---- 02: the water-balance sketch ----
// Made-up ten days of reference evapotranspiration (mm) and rain (mm); illustration only.
const WEATHER = [
  { et0: 4.6 }, { et0: 5.2 }, { et0: 6.4, mark: "heat" }, { et0: 6.8, mark: "heat" }, { et0: 2.4, rain: 9, mark: "rain" },
  { et0: 3.0, rain: 3, mark: "rain" }, { et0: 4.2 }, { et0: 4.8 }, { et0: 5.0 }, { et0: 5.4 },
];
const PLANTS = {
  succulent: { name: "Aloe", glyph: "succulent", k: 0.35, waterAt: 0.1 },
  herb: { name: "Basil", glyph: "herb", k: 1.0, waterAt: 0.45 },
  fern: { name: "Fern", glyph: "fern", k: 0.8, waterAt: 0.65 },
};
const MATERIAL = { plastic: 1.0, glazed: 1.05, terracotta: 1.3 };
const SPOT = { sun: { light: 1.0, rain: 0.8 }, covered: { light: 0.7, rain: 0 }, indoor: { light: 0.25, rain: 0 } };

const form = document.getElementById("controls");
let learned = 1; // the app's current belief about how thirsty this plant is, relative to its type
let answers = 0;

function simulate() {
  const f = new FormData(form);
  const plant = PLANTS[f.get("plant")];
  const width = Number(f.get("width"));
  const spot = SPOT[f.get("spot")];
  const litres = 0.5 * (width / 10) ** 3; // a "standard" pot about as tall as it is wide
  const capacity = litres * 1000 * 0.35; // ml the roots can reach
  const canopy = Math.PI * ((width * 1.1) / 200) ** 2; // m², plant a little wider than its pot
  const top = Math.PI * (width / 200) ** 2;
  let left = capacity;
  const days = WEATHER.map((w, i) => {
    const action = left / capacity <= plant.waterAt;
    const ml = capacity - left;
    if (action) left = capacity;
    const pct = (left / capacity) * 100; // water left this morning, after any watering
    const use = w.et0 * spot.light * canopy * 1000 * MATERIAL[f.get("material")] * plant.k * learned * 0.7;
    const rain = (w.rain ?? 0) * top * 1000 * spot.rain;
    left = Math.max(0, Math.min(capacity, left - use + rain));
    return { ...w, i, action, ml, pct };
  });
  return { plant, days };
}

function render() {
  const f = new FormData(form);
  form.querySelector('output[data-for="width"]').textContent = `${f.get("width")} cm`;
  const { plant, days } = simulate();
  const next = days.find((d) => d.action);
  const nextMl = next ? Math.round(next.ml / 10) * 10 : 0;
  const card = document.getElementById("demo-card");
  if (next && next.i <= 1) {
    card.innerHTML = `<div class="card sky big"><div class="m-row"><div><div class="m-name">${plant.name}</div><div>Water ${next.i === 0 ? "today" : "tomorrow"}, about ${nextMl} ml</div></div>${glyph(plant.glyph, INK)}</div></div>`;
  } else if (next) {
    card.innerHTML = `<div class="card moss big"><div class="m-row"><div><div class="m-name">${plant.name}</div><div>Fine. Water ${dayName(next.i)}, about ${nextMl} ml.</div></div>${glyph(plant.glyph, WHITE)}</div></div>`;
  } else {
    card.innerHTML = `<div class="card moss big"><div class="m-row"><div><div class="m-name">${plant.name}</div><div>Fine for the next ten days.</div></div>${glyph(plant.glyph, WHITE)}</div></div>`;
  }
  const strip = document.getElementById("strip");
  strip.setAttribute("aria-label", `Next ten days. ${next ? `Next watering ${dayName(next.i)}.` : "No watering needed."}`);
  strip.innerHTML = days.map((d) => {
    const mark = d.mark === "heat" ? `<span class="mk">${SUN}</span>` : d.mark === "rain" ? `<span class="mk rain">${rainIcon}</span>` : `<span class="mk"></span>`;
    return `<div class="col${d === next ? " next" : ""}${d.action ? " water" : ""}">${mark}<span class="bar"><span style="height:${Math.max(3, d.pct)}%"></span></span><span class="wd">${dayName(d.i).slice(0, 1)}</span><span class="drop" aria-hidden="true">${d.action ? "●" : ""}</span></div>`;
  }).join("");
}
const rainIcon = `<svg width="24" height="24" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3C12 3 6 10.5 6 14.5a6 6 0 0 0 12 0C18 10.5 12 3 12 3Z" fill="currentColor"/></svg>`;

function renderLearning() {
  document.getElementById("learn-text").textContent = answers >= 8 ? "Knows this plant well" : `Still learning: ${answers} of about 8 answers`;
  document.getElementById("dots").innerHTML = Array.from({ length: 8 }, (_, i) => `<i class="${i < answers ? "on" : ""}"></i>`).join("");
}

form.addEventListener("input", () => { learned = 1; answers = 0; renderLearning(); render(); });
document.querySelectorAll("[data-answer]").forEach((b) =>
  b.addEventListener("click", () => {
    // Sketch of the idea only: wet soil means it drinks less than thought, dry soil more.
    const step = 0.25 / (1 + answers * 0.5);
    if (b.dataset.answer === "wet") learned *= 1 - step;
    if (b.dataset.answer === "dry") learned *= 1 + step;
    answers = Math.min(8, answers + 1);
    renderLearning();
    render();
  }),
);
document.getElementById("forget").addEventListener("click", () => { learned = 1; answers = 0; renderLearning(); render(); });
renderLearning();
render();

// ---- 03: alert cards ----
document.getElementById("alerts").innerHTML = `
  <div class="card brick alert">${SNOW}<span>Frost ${dayName(5)}–${dayName(7)}: Boris, Rosemary. Lift off the floor and wrap the pots.</span></div>
  <div class="card brick alert">${SUN}<span>Heat ${dayName(2)}–${dayName(3)}: Basil. Water in the morning, shade at noon.</span></div>
  <div class="card brick alert">${WIND}<span>Strong wind ${dayName(4)}: Boris. Move it away from the railing.</span></div>`;

// ---- 04: simulation results (docs/reports/sim-report.md, provider ET0) ----
const SIM = [
  { place: "Belgrade, outdoor", engine: 18.2, baseline: 50.4 },
  { place: "Belgrade, indoor", engine: 38.6, baseline: 103.6 },
  { place: "Seville, outdoor", engine: 22.9, baseline: 39.7 },
  { place: "Edinburgh, outdoor", engine: 18.7, baseline: 39.8 },
];
const max = 110;
document.getElementById("chart").innerHTML = `
  <div class="legend"><span><i class="k-engine"></i>Overgrown engine</span><span><i class="k-base"></i>Fixed-interval baseline</span></div>
  ${SIM.map((r) => `<div class="row"><span class="place">${r.place}</span>
    <span class="bars">
      <span class="b engine" style="width:${(r.engine / max) * 100}%"><em>${r.engine}</em></span>
      <span class="b base" style="width:${(r.baseline / max) * 100}%"><em>${r.baseline}</em></span>
    </span></div>`).join("")}`;
