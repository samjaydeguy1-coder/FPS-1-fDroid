const $ = id => document.getElementById(id);
let badgeMap = {};

function badgeFor(team) {
  const item = badgeMap[team];
  if (!item) return "";
  return typeof item === "string" ? item : (item.file || item.filename || item.src || "");
}

function setBadge(img, team) {
  const file = badgeFor(team);
  if (!file) {
    img.hidden = true;
    img.removeAttribute("src");
    return;
  }
  img.src = file.startsWith("/") ? file : `/static/badges/${file}${/\.(png|svg|webp)$/i.test(file) ? "" : ".png"}`;
  img.hidden = false;
  img.onerror = () => { img.hidden = true; };
}

function updateSelectBadge(side) {
  const select = $(side);
  const img = $(`${side}Badge`);
  const team = select.value;
  setBadge(img, team);
  select.classList.toggle("has-badge", !!badgeFor(team));
}

async function getJSON(url, options) {
  const response = await fetch(url, options);
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || `Request failed (${response.status})`);
  return data;
}

async function loadTeams() {
  const league = $("league").value;
  $("home").innerHTML = '<option value="">Select home team</option>';
  $("away").innerHTML = '<option value="">Select away team</option>';
  $("results").hidden = true;
  if (!league) return;

  $("status").textContent = "Loading teams...";
  const data = await getJSON(`/api/teams?league=${encodeURIComponent(league)}`);
  for (const team of data.teams || []) {
    for (const side of ["home", "away"]) {
      const option = document.createElement("option");
      option.value = team;
      option.textContent = team;
      $(side).appendChild(option);
    }
  }
  $("status").textContent = `${(data.teams || []).length} teams available`;
}

function percent(value) {
  return `${(Number(value) * 100).toFixed(1)}%`;
}

async function predict() {
  const league = $("league").value;
  const home = $("home").value;
  const away = $("away").value;
  if (!league || !home || !away) {
    $("status").textContent = "Select a league, home team and away team.";
    return;
  }
  if (home === away) {
    $("status").textContent = "Choose two different teams.";
    return;
  }

  const button = $("predict");
  button.disabled = true;
  button.textContent = "ANALYSING MATCH...";
  $("status").textContent = "Running FPS-1 prediction model...";
  $("results").hidden = true;

  try {
    const data = await getJSON("/api/predict", {
      method: "POST",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify({league, home, away})
    });

    $("resultHome").textContent = data.home;
    $("resultAway").textContent = data.away;
    setBadge($("resultHomeBadge"), data.home);
    setBadge($("resultAwayBadge"), data.away);

    const p = data.probabilities;
    for (const [key, id] of [["home","home"],["draw","draw"],["away","away"]]) {
      $(id + "Prob").textContent = percent(p[key]);
      $(id + "Bar").style.width = `${Number(p[key]) * 100}%`;
    }

    $("expectedHome").textContent = Number(data.expected_goals.home).toFixed(2);
    $("expectedAway").textContent = Number(data.expected_goals.away).toFixed(2);
    $("verdict").textContent = data.most_likely_result;

    const scores = $("scores");
    scores.innerHTML = "";
    for (const item of data.top_scores || []) {
      const card = document.createElement("div");
      card.className = "score";
      const score = document.createElement("strong");
      score.textContent = item.score;
      const probability = document.createElement("span");
      probability.textContent = percent(item.probability);
      card.append(score, probability);
      scores.appendChild(card);
    }

    $("results").hidden = false;
    $("status").textContent = "Analysis complete.";
    $("results").scrollIntoView({behavior:"smooth", block:"start"});
  } catch (error) {
    $("status").textContent = `Prediction error: ${error.message}`;
  } finally {
    button.disabled = false;
    button.innerHTML = 'ANALYSE MATCH <span>→</span>';
  }
}

async function initialise() {
  try {
    const [leagueData, badges] = await Promise.all([
      getJSON("/api/leagues"),
      getJSON("/static/team_badges.json")
    ]);
    badgeMap = badges;
    $("league").innerHTML = "";
    for (const league of leagueData.leagues || []) {
      const option = document.createElement("option");
      option.value = league.id;
      option.textContent = league.name;
      $("league").appendChild(option);
    }
    await loadTeams();
  } catch (error) {
    $("status").textContent = `Could not initialise FPS-1: ${error.message}`;
  }
}

$("league").addEventListener("change", () => loadTeams().catch(e => $("status").textContent = e.message));
$("home").addEventListener("change", () => updateSelectBadge("home"));
$("away").addEventListener("change", () => updateSelectBadge("away"));
$("predict").addEventListener("click", predict);
initialise();
