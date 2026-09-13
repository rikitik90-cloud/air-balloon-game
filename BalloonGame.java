// ============================================================
//  ВОЗДУШНЫЙ ШАР — бонусная crash-игра (прототип MVP)
//  Чемпионат России по продуктовому программированию
//
//  ЗАПУСК (Java 17+, без зависимостей):
//    javac BalloonGame.java
//    java BalloonGame          -> http://localhost:8080
//    java BalloonGame 9090     -> другой порт
//
//  Демо-доступ: просто открыть URL. Баланс: кнопка «+100 бонусов».
//  Если рядом с классом лежит index.html — раздаётся он,
//  иначе используется встроенная копия (однофайловый режим).
//
//  Конфигурация: файл config.json (создаётся автоматически)
//  или UI «Параметры». Применяется со следующего раунда.
//
//  Математическая модель: см. MATH.md (раздел 10 ТЗ).
//  Режим разработчика: параметр "dev_seed" в конфиге —
//  детерминированная генерация краха для отладки.
// ============================================================
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.TreeMap;
import java.util.concurrent.Executors;

public class BalloonGame {

  static final String VERSION = "2.1.16";
  static final int DEFAULT_PORT = 8080;
  static final Path CFG_PATH = Path.of("config.json");
  static final SecureRandom RNG = new SecureRandom();

  static Map<String, Object> CFG = new LinkedHashMap<>();
  static final Map<String, User> USERS = new ConcurrentHashMap<>();
  static final Map<String, Round> ROUNDS = new ConcurrentHashMap<>();
  static final List<Map<String, Object>> HISTORY = Collections.synchronizedList(new ArrayList<>());
  static final Map<String, String> TOKENS = new ConcurrentHashMap<>();
  static final Map<String, String> BY_EMAIL = new ConcurrentHashMap<>();
  static String leaderDay = "";
  static final List<Map<String, Object>> LEADER_SNAPS = new ArrayList<>();
  static final Path USERS_PATH = Path.of("users.json");
  static final Path LEADERS_PATH = Path.of("leaders.json");

  static final String DEFAULT_CFG = "{\"game_id\":\"air-balloon\",\"game_name\":\"Воздушный полет\",\"game_type\":\"crash\",\"is_active\":true,\"dev_seed\":null,\"edge\":0.95,\"alpha\":1.2,\"max_multiplier\":1000.0,\"min_crash_multiplier\":1.0,\"multiplier_growth_rate\":0.005,\"growth_curve\":1.8,\"balloon_top_at\":5.0,\"balloon_max_h\":0.55,\"level_gap_scale\":2.6,\"fps\":60,\"delta\":0.0167,\"level_step\":1.0,\"idle_timeout_sec\":10,\"starting_balance\":500,\"points_per_line\":10,\"points_cashout_bonus\":25,\"points_xN_bonus\":20,\"themes\":{\"RED\":{\"levels\":12,\"line_loot_prob\":[0.20,0.18,0.15,0.12,0.10,0.08,0.06,0.05,0.04,0.01,0.005,0.005]},\"GREEN\":{\"levels\":9,\"line_loot_prob\":[0.26,0.22,0.18,0.12,0.09,0.06,0.04,0.02,0.01]}},\"booster_values\":{\"1\":1,\"2\":2,\"3\":3,\"4\":4},\"min_bet\":1,\"boosters\":[1,2,3,4],\"altitude\":{\"mid\":2.0,\"high\":5.0,\"galaxy\":1000.0},\"puzzle_rate\":10,\"gift_threshold\":100,\"gift_prizes\":[{\"amount\":10,\"weight\":1000},{\"amount\":100,\"weight\":100},{\"amount\":1000,\"weight\":10},{\"amount\":10000,\"weight\":1}],\"random_sounds\":{\"interval_sec\":5,\"enabled\":true},\"leaderboard_reward\":\"1:1000, 2:500, 3:250\",\"assets\":{\"balloons\":{\"RED\":null,\"GREEN\":null},\"launchpad\":null,\"clouds\":[],\"birds\":[],\"sounds\":{\"theme\":null,\"click\":null,\"level\":null,\"boost\":null,\"cashout\":null,\"crash\":null,\"flight\":null,\"ambient\":null,\"reel\":null,\"jackpot\":null},\"icons\":{\"balance\":null,\"points\":null,\"rules\":null,\"reward\":null},\"fonts\":{\"regular\":null,\"bold\":null,\"family\":null},\"sfx\":[]}}";

  static final String EMBEDDED_INDEX = """
<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Воздушный полет — бонусная crash-игра</title>
<style>
:root{--red:#e84545;--green:#37b568;--gold:#ffce3a;--ink:#1e2a44}
*{box-sizing:border-box;margin:0;padding:0}
html,body{height:100%}
body{font-family:"Segoe UI",system-ui,-apple-system,sans-serif;color:var(--ink);background:linear-gradient(180deg,#6db9ec 0%,#a8d8f5 45%,#e9f7ff 78%,#fff4d6 100%);min-height:100vh;overflow-x:hidden}
.screen{display:none;width:100%;min-height:100vh;padding:18px 14px 40px;flex-direction:column;align-items:center;position:relative}
.screen.active{display:flex}
h1{font-size:clamp(28px,6vw,52px);text-shadow:0 2px 0 rgba(255,255,255,.6)}
.muted{opacity:.75}
button{font:inherit;cursor:pointer;border:0;border-radius:12px;padding:12px 22px;font-weight:700;background:linear-gradient(180deg,#ffd54a,#ffb300);color:#5a3b00;box-shadow:0 4px 0 #cc8f00,0 8px 16px rgba(0,0,0,.15);transition:.15s}
button:hover{transform:translateY(-2px)}
button:active{transform:translateY(1px);box-shadow:0 2px 0 #cc8f00}
button:disabled{filter:grayscale(.8);opacity:.55;cursor:not-allowed;transform:none}
.btn-ghost{background:#fff;color:var(--ink);box-shadow:0 3px 0 #c9d6e8}
.topbar{display:flex;gap:10px;flex-wrap:wrap;align-items:center;justify-content:center;width:100%;max-width:900px;margin-bottom:12px}
.chip{background:rgba(255,255,255,.85);border-radius:999px;padding:8px 16px;font-weight:700;box-shadow:0 2px 8px rgba(30,60,100,.12)}
.theme-wrap{display:flex;gap:clamp(20px,8vw,90px);margin-top:6vh;flex-wrap:wrap;justify-content:center}
.balloon-pick{width:clamp(120px,26vw,180px);text-align:center;cursor:pointer;transition:.2s}
.balloon-pick:hover{transform:translateY(-8px) scale(1.04)}
.balloon-svg{width:100%;animation:float 3.8s ease-in-out infinite}
.balloon-pick:nth-child(2) .balloon-svg{animation-duration:4.6s;animation-delay:-1.4s}
@keyframes float{0%,100%{transform:translateY(0) rotate(-2deg)}50%{transform:translateY(-18px) rotate(2deg)}}
@keyframes drift{from{transform:translateX(-18vw)}to{transform:translateX(118vw)}}
.cloud{position:fixed;background:rgba(255,255,255,.85);border-radius:999px;z-index:0;animation:drift linear infinite;pointer-events:none}
.cloud:before,.cloud:after{content:"";position:absolute;background:inherit;border-radius:50%}
.cloud:before{width:60%;height:160%;top:-70%;left:12%}
.cloud:after{width:45%;height:120%;top:-40%;right:10%}
.bird{position:fixed;font-size:22px;z-index:0;animation:drift linear infinite;pointer-events:none}
.lvl-badge{display:inline-block;margin-top:8px;background:#fff;border-radius:999px;padding:6px 14px;font-weight:800;box-shadow:0 2px 8px rgba(0,0,0,.12)}
.puzzles{display:grid;grid-template-columns:repeat(4,minmax(110px,150px));gap:14px;justify-content:center;margin:18px 0}
.puzzle{aspect-ratio:1/1.15;background:linear-gradient(160deg,#fffdf5,#ffe7a8);clip-path:polygon(50% 0,100% 25%,100% 75%,50% 100%,0 75%,0 25%);display:flex;flex-direction:column;align-items:center;justify-content:center;gap:3px;cursor:pointer;font-weight:800;transition:.18s}
.puzzle:hover{transform:translateY(-4px)}
.puzzle .cost{font-size:clamp(18px,3vw,24px)}
.puzzle .boost{color:#b45309;font-size:clamp(13px,2.2vw,17px)}
.puzzle.sel{background:linear-gradient(160deg,#fff3c4,#ffce3a);transform:scale(1.07);filter:drop-shadow(0 0 12px rgba(255,206,58,.9))}
.puzzle.poor{opacity:.45}
.hist{max-width:680px;width:100%;margin-top:22px;background:rgba(255,255,255,.75);border-radius:14px;padding:12px 16px;max-height:230px;overflow:auto}
.hist h3{margin-bottom:8px}
.hist-row{display:flex;gap:10px;justify-content:space-between;padding:5px 0;border-bottom:1px dashed #cbd8ea;font-size:14px}
.hist-row .w{color:#1a9c4b;font-weight:700}
.hist-row .l{color:#d33;font-weight:700}
.theme-switch{display:flex;gap:8px}
#scr-game{padding:0;overflow:hidden}
#flight{position:relative;width:100%;height:100vh;min-height:480px;overflow:hidden}
#mult{position:absolute;top:12px;left:50%;transform:translateX(-50%);font-size:clamp(44px,9vw,86px);font-weight:900;z-index:5;transition:color .2s}
#mult.t1{color:#22304d}
#mult.t2{color:#e0a800}
#mult.t3{color:#ffc400;text-shadow:0 0 20px #ffe58a}
#mult.t4{color:#ffbf00;text-shadow:0 0 30px #ffd54a,0 0 60px #ffb300}
#sub-mult{position:absolute;top:calc(14px + clamp(44px,9vw,86px));left:50%;transform:translateX(-50%);font-weight:800;color:#7a5b00;background:rgba(255,255,255,.85);padding:2px 12px;border-radius:999px;display:none;z-index:5}
#ladder{position:absolute;right:8px;top:80px;bottom:130px;width:min(46vw,300px);z-index:2}
.ladd-line{position:absolute;left:0;right:0;border-top:2px dashed rgba(30,60,100,.35)}
.ladd-line span{position:absolute;right:4px;top:-18px;font-size:11px;font-weight:700;background:rgba(255,255,255,.75);padding:1px 7px;border-radius:6px}
.boost-icon{position:absolute;right:12px;transform:translateY(50%);font-size:24px;filter:drop-shadow(0 0 6px #ffce3a)}
#balloon{position:absolute;left:16%;width:clamp(80px,16vw,140px);z-index:3;bottom:6%}
#balloon svg{width:100%;display:block}
#boom{position:absolute;font-size:90px;display:none;z-index:4}
.pt{position:absolute;font-weight:900;color:#1a9c4b;font-size:22px;z-index:6;animation:rise 1.1s ease-out forwards;text-shadow:0 1px 0 #fff}
@keyframes rise{from{opacity:1;transform:translateY(0)}to{opacity:0;transform:translateY(-70px)}}
#btn-cashout{position:absolute;bottom:26px;left:50%;transform:translateX(-50%);z-index:7;font-size:clamp(18px,3.4vw,26px);padding:16px 44px;border-radius:18px;background:linear-gradient(180deg,#5be584,#1fae57);color:#fff;box-shadow:0 5px 0 #14803c,0 10px 20px rgba(0,0,0,.2)}
#btn-cashout:disabled{background:#9db3c8;box-shadow:0 5px 0 #7b90a6}
#toast{position:fixed;top:14px;left:50%;transform:translateX(-50%);background:#22304d;color:#fff;padding:10px 20px;border-radius:12px;font-weight:700;z-index:200;opacity:0;transition:.3s;pointer-events:none;max-width:92vw;text-align:center}
#toast.show{opacity:1}
#fx{position:fixed;inset:0;pointer-events:none;z-index:150}
#hash-line{position:absolute;bottom:4px;left:8px;font-size:11px;opacity:.6;z-index:5;max-width:62vw;word-break:break-all}
#onboard{position:absolute;bottom:120px;left:50%;transform:translateX(-50%);background:#22304d;color:#fff;padding:12px 18px;border-radius:14px;z-index:8;font-weight:700;display:none;max-width:88vw;text-align:center}
#onboard:after{content:"";position:absolute;top:100%;left:50%;margin-left:-10px;border:10px solid transparent;border-top-color:#22304d}
#boost-banner{position:absolute;top:30%;left:50%;transform:translateX(-50%) scale(.6);font-size:clamp(26px,6vw,48px);font-weight:900;color:#ff8f00;text-shadow:0 0 24px #ffd54a;z-index:6;opacity:0;transition:.25s;pointer-events:none}
#boost-banner.show{opacity:1;transform:translateX(-50%) scale(1)}
body.shake{animation:shake .5s}
@keyframes shake{0%,100%{transform:none}20%{transform:translate(-8px,4px)}40%{transform:translate(7px,-5px)}60%{transform:translate(-5px,-3px)}80%{transform:translate(4px,4px)}}
.res-card{background:rgba(255,255,255,.92);border-radius:22px;padding:26px 30px;max-width:520px;width:100%;text-align:center;box-shadow:0 16px 40px rgba(30,60,100,.25);margin-top:5vh}
.res-card h2{font-size:clamp(24px,5vw,36px);margin-bottom:10px}
.res-win{font-size:clamp(30px,7vw,46px);font-weight:900;color:#1a9c4b}
.res-lose{color:#d33;font-size:clamp(22px,5vw,32px);font-weight:900}
.res-row{display:flex;justify-content:space-between;padding:8px 4px;border-bottom:1px dashed #d4deee;font-weight:600}
.res-timer{margin-top:14px;font-size:13px;opacity:.7}
.fair{margin-top:12px;font-size:11px;opacity:.65;word-break:break-all;text-align:left}
.modal{display:none;position:fixed;inset:0;background:rgba(20,35,60,.55);z-index:100;align-items:center;justify-content:center;padding:16px}
.modal.open{display:flex}
.modal-card{background:#fff;border-radius:18px;max-width:640px;width:100%;max-height:86vh;overflow:auto;padding:22px}
.modal-card h3{margin-bottom:12px}
.modal-card textarea{width:100%;height:320px;font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px;border-radius:10px;border:1px solid #c9d6e8;padding:10px}
.modal-actions{display:flex;gap:10px;margin-top:12px;justify-content:flex-end;flex-wrap:wrap}
.rules-list li{margin:8px 0 8px 18px}
</style>
</head>
<body>
<div class="cloud" style="width:120px;height:38px;top:12%;animation-duration:55s"></div>
<div class="cloud" style="width:180px;height:48px;top:26%;animation-duration:75s;animation-delay:-30s"></div>
<div class="cloud" style="width:90px;height:30px;top:6%;animation-duration:95s;animation-delay:-60s"></div>
<div class="bird" style="top:16%;animation-duration:34s">🕊️</div>
<div class="bird" style="top:34%;animation-duration:48s;animation-delay:-20s">🐦</div>

<section id="scr-theme" class="screen active">
  <h1>🎈 Воздушный полет</h1>
  <p class="muted">Бонусная crash-игра · нажми «Забрать» до того, как шар лопнет</p>
  <div class="theme-wrap">
    <div class="balloon-pick" data-theme="RED">
      <div class="balloon-svg" data-bsvg="RED"></div>
      <div class="lvl-badge">🔴 Красный шар · 12 уровней</div>
    </div>
    <div class="balloon-pick" data-theme="GREEN">
      <div class="balloon-svg" data-bsvg="GREEN"></div>
      <div class="lvl-badge">🟢 Зелёный шар · 9 уровней</div>
    </div>
  </div>
  <p class="muted" style="margin-top:5vh">Баланс: <b id="theme-balance">—</b> бонусов</p>
</section>

<section id="scr-bet" class="screen">
  <div class="topbar">
    <span class="chip">💰 <b id="bet-balance">—</b></span>
    <span class="chip">⭐ <b id="bet-points">—</b></span>
    <span class="chip" id="bet-theme-label"></span>
    <div class="theme-switch">
      <button class="btn-ghost" id="sw-red">🔴 12 ур.</button>
      <button class="btn-ghost" id="sw-green">🟢 9 ур.</button>
    </div>
  </div>
  <div class="topbar">
    <button class="btn-ghost" id="btn-rules">📜 Правила</button>
    <button class="btn-ghost" id="btn-config">⚙️ Параметры</button>
    <button class="btn-ghost" id="btn-refill">+100 бонусов</button>
    <button class="btn-ghost" id="btn-back-theme">← Темы</button>
  </div>
  <h2 style="margin-top:6px">Выберите ставку</h2>
  <p class="muted">Один фрагмент пазла = ставка + бустер</p>
  <div class="puzzles" id="puzzles"></div>
  <button id="btn-start" disabled>Начать полёт 🎈</button>
  <div class="hist"><h3>📋 История игр</h3><div id="history"></div></div>
</section>

<section id="scr-game" class="screen">
  <div id="flight">
    <div id="mult">×1.00</div>
    <div id="sub-mult"></div>
    <div id="ladder"></div>
    <div id="balloon"></div>
    <div id="boom">💥</div>
    <div id="boost-banner">🚀 БУСТ ×<span id="boost-val"></span>!</div>
    <button id="btn-cashout" disabled>Забрать</button>
    <div id="onboard">Нажми «Забрать» до того, как шар лопнет ⬇</div>
    <div id="hash-line"></div>
  </div>
</section>

<section id="scr-result" class="screen">
  <div class="res-card">
    <h2 id="res-title"></h2>
    <div id="res-amount" class="res-win"></div>
    <div id="res-mult" class="muted" style="font-weight:700;margin:6px 0"></div>
    <div id="res-more" class="muted"></div>
    <div class="res-row"><span>Очки за раунд</span><b id="res-points"></b></div>
    <div class="res-row"><span>Всего очков</span><b id="res-total-points"></b></div>
    <div class="res-row"><span>Награда</span><b id="res-reward"></b></div>
    <div class="fair" id="res-fair"></div>
    <div class="fair" id="res-verify"></div>
    <div style="display:flex;gap:10px;justify-content:center;margin-top:18px;flex-wrap:wrap">
      <button id="btn-again">Играть снова</button>
      <button id="btn-to-theme" class="btn-ghost">К темам</button>
    </div>
    <div class="res-timer" id="res-timer"></div>
  </div>
</section>

<div class="modal" id="modal-rules"><div class="modal-card">
  <h3>📜 Правила игры</h3>
  <ul class="rules-list" id="rules-list"></ul>
  <div class="modal-actions"><button class="btn-ghost" data-close="modal-rules">Закрыть</button></div>
</div></div>

<div class="modal" id="modal-config"><div class="modal-card">
  <h3>⚙️ Параметры игры</h3>
  <p class="muted" style="margin-bottom:8px">Горячее применение — со следующего раунда, без правки кода.</p>
  <textarea id="cfg-json" spellcheck="false"></textarea>
  <div class="modal-actions">
    <button class="btn-ghost" data-close="modal-config">Отмена</button>
    <button id="btn-cfg-save">Сохранить</button>
  </div>
</div></div>

<div id="toast"></div>
<canvas id="fx"></canvas>
<script>
"use strict";
const $=id=>document.getElementById(id);
const esc=s=>String(s).replace(/&/g,"&amp;").replace(/</g,"&lt;");
let CFG=null,USER=null,Backend=null,theme="RED",betIndex=-1;
let round=null,raf=0,poll=0,lastFrame=0,startMark=0,serverMult=1,polled=false;
let prevLevels=0,boosterOn=false,boostTier=1,cashed=false,cashWin=0,cashMult=0;
let idleInt=0,onboardDone=localStorage.getItem("ab_onboard")==="1";
const enc=new TextEncoder();
let AC=null;
function ac(){if(!AC){try{AC=new (window.AudioContext||window.webkitAudioContext)()}catch(e){AC=null}}return AC}
function tone(f,d,type,g){const a=ac();if(!a)return;try{const o=a.createOscillator(),v=a.createGain();o.type=type||"sine";o.frequency.value=f;v.gain.value=g||0.05;o.connect(v);v.connect(a.destination);o.start();v.gain.exponentialRampToValueAtTime(0.001,a.currentTime+d);o.stop(a.currentTime+d)}catch(e){}}
function toast(msg,ms){const t=$("toast");t.textContent=msg;t.classList.add("show");clearTimeout(t._h);t._h=setTimeout(()=>t.classList.remove("show"),ms||2600)}
function show(id){document.querySelectorAll(".screen").forEach(s=>s.classList.remove("active"));$(id).classList.add("active");stopIdle()}
async function api(path,opt){const res=await fetch(path,Object.assign({headers:{"Content-Type":"application/json"}},opt||{}));const txt=await res.text();let data=null;try{data=JSON.parse(txt)}catch(e){}if(!res.ok)throw new Error((data&&data.error)||txt||("HTTP "+res.status));return data}
async function sha256Hex(s){const b=await crypto.subtle.digest("SHA-256",enc.encode(s));return Array.from(new Uint8Array(b)).map(x=>x.toString(16).padStart(2,"0")).join("")}

const ServerBackend={mode:"server",cfg:null,
 async config(){const c=await api("/api/config");this.cfg=c;return c},
 async user(){return api("/api/user")},
 async start(theme,betIndex){return api("/api/round/start",{method:"POST",body:JSON.stringify({theme:theme,betIndex:betIndex})})},
 async state(id){return api("/api/round/state?id="+encodeURIComponent(id))},
 async cashout(id){return api("/api/round/cashout",{method:"POST",body:JSON.stringify({id:id})})},
 async history(){return api("/api/history")},
 async saveConfig(c){await api("/api/config",{method:"POST",body:JSON.stringify(c)});this.cfg=c;return c},
 async addBalance(a){return api("/api/balance/add",{method:"POST",body:JSON.stringify({amount:a})})},
 async verify(id){return api("/api/round/verify?id="+encodeURIComponent(id))}
};

const LStore={get(k,d){try{const v=localStorage.getItem(k);return v?JSON.parse(v):d}catch(e){return d}},set(k,v){try{localStorage.setItem(k,JSON.stringify(v))}catch(e){}}};
const LocalBackend={mode:"local",cfg:null,rounds:{},
 defaults(){return {game_id:"air-balloon",game_name:"Воздушный полет",game_type:"crash",is_active:true,dev_seed:null,edge:0.95,alpha:1.2,max_multiplier:50,min_crash_multiplier:1,multiplier_growth_rate:0.42,fps:60,delta:0.0167,level_step:0.5,idle_timeout_sec:10,starting_balance:500,points_per_line:10,points_cashout_bonus:25,points_xN_bonus:20,themes:{RED:{levels:12,line_loot_prob:[0.20,0.18,0.15,0.12,0.10,0.08,0.06,0.05,0.04,0.01,0.005,0.005]},GREEN:{levels:9,line_loot_prob:[0.26,0.22,0.18,0.12,0.09,0.06,0.04,0.02,0.01]}},booster_values:{"1":1,"2":2,"3":3,"4":4},bets:[{cost:10,booster:1},{cost:25,booster:2},{cost:50,booster:3},{cost:100,booster:4}]}},
 async config(){this.cfg=LStore.get("ab_cfg",null)||this.defaults();return this.cfg},
 async saveConfig(c){LStore.set("ab_cfg",c);this.cfg=c;return c},
 async user(){let u=LStore.get("ab_user",null);if(!u){u={id:"demo",name:"Игрок Демо",bonusBalance:this.cfg.starting_balance,points:0,tickets:0,fragments:{}};LStore.set("ab_user",u)}return u},
 async addBalance(a){const u=await this.user();u.bonusBalance+=a;LStore.set("ab_user",u);return u},
 async history(){let h=LStore.get("ab_hist",null);if(!h){h=this.seedBots();LStore.set("ab_hist",h)}return h},
 seedBots(){const names=["Ан***","Мар***","Ив***","Оль***","Дм***","Св***","Ки***","Не***","Па***","Ел***","Та***","Ро***"];const out=[];for(let i=0;i<14;i++){const th=i%2?"GREEN":"RED";const bet=this.cfg.bets[Math.floor(Math.random()*4)];const coef=this.genCrash(th);const winF=Math.random()<0.42;const coefOut=winF?Math.max(1.01,coef*0.55):coef;const win=winF?Math.floor(bet.cost*coefOut):0;out.push({user:names[Math.floor(Math.random()*names.length)],theme:th,bet:bet.cost,booster:bet.booster,coef:Math.round(coefOut*100)/100,result:winF?"WIN":"LOSS",win:win,time:new Date(Date.now()-Math.floor(Math.random()*72e5)).toISOString()})}return out},
 themeCfg(t){return this.cfg.themes[t]},
 genCrash(){const cfg=this.cfg;let u=Math.random();if(cfg.dev_seed){u=this.devU()}const e=-Math.log(1-u);let c=(1+e/cfg.alpha)*cfg.edge;if(c<cfg.min_crash_multiplier)c=cfg.min_crash_multiplier;if(c>cfg.max_multiplier)c=cfg.min_crash_multiplier;return Math.round(c*100)/100},
 devU(){return Math.random()},
 pickBooster(t){const p=this.themeCfg(t).line_loot_prob;const sum=p.reduce((a,b)=>a+b,0);let u=Math.random()*sum,acc=0;for(let i=0;i<p.length;i++){acc+=p[i];if(u<acc)return i+1}return p.length},
 async start(theme,bi){const cfg=this.cfg,u=await this.user();const bet=cfg.bets[bi];if(!bet)throw new Error("Нет такой ставки");if(u.bonusBalance<bet.cost)throw new Error("Не хватает бонусов");u.bonusBalance-=bet.cost;LStore.set("ab_user",u);
  const id="L"+Date.now().toString(36)+Math.floor(Math.random()*999);const r={id:id,theme:theme,betCost:bet.cost,boosterTier:bet.booster,status:"FLYING",start:Date.now(),crashPoint:this.genCrash(theme),boosterLevel:this.pickBooster(theme),seed:this.hex(16),hash:"",cashedOut:false,win:0,points:0,cashoutMult:0};
  r.hash=await this.sha(r.seed+"|"+r.id+"|"+r.crashPoint.toFixed(2));this.rounds[id]=r;
  return {id:id,hash:r.hash,boosterLevel:r.boosterLevel,balance:u.bonusBalance}},
 calc(r){const cfg=this.cfg;const el=(Date.now()-r.start)/1000;let mult=Math.exp(cfg.multiplier_growth_rate*el);mult=Math.floor(mult*100)/100;if(mult<1)mult=1;const th=this.themeCfg(r.theme);const passed=Math.min(th.levels,Math.max(0,Math.floor((mult-1)/cfg.level_step)));const bon=r.boosterLevel>0&&passed>=r.boosterLevel;const eff=bon?Math.floor(mult*r.boosterTier*100)/100:mult;return {mult:mult,passed:passed,bon:bon,eff:eff,crashed:mult>=r.crashPoint,th:th}},
 async state(id){const r=this.rounds[id];if(!r)throw new Error("Раунд не найден");const s=this.calc(r);
  const out={id:r.id,status:r.status,multiplier:s.mult,effMultiplier:s.eff,levelsPassed:s.passed,boosterActivated:s.bon,maxLevels:s.th.levels,hash:r.hash};
  if(r.status==="CASED_OUT")out.cashoutMultiplier=r.cashoutMult;
  if(s.crashed&&r.status!=="CRASHED"){r.status="CRASHED";if(!r.cashedOut){r.win=0;r.points=s.passed*this.cfg.points_per_line+(s.bon?this.cfg.points_xN_bonus:0)}const u=await this.user();u.points+=r.points;LStore.set("ab_user",u);this.pushHist(r);
   out.status="CRASHED";out.crashPoint=r.crashPoint;out.seed=r.seed;out.points=r.points;out.winAmount=r.win}
  return out},
 async cashout(id){const r=this.rounds[id];if(!r)throw new Error("Раунд не найден");if(r.status==="CRASHED")throw new Error("Раунд завершён");if(r.status==="CASED_OUT")throw new Error("Уже забрано");
  const s=this.calc(r);const out={success:false};
  if(s.crashed){r.status="CRASHED";r.cashedOut=false;r.win=0;r.points=s.passed*this.cfg.points_per_line+(s.bon?this.cfg.points_xN_bonus:0);const u=await this.user();u.points+=r.points;LStore.set("ab_user",u);this.pushHist(r);
   out.status="CRASHED";out.crashPoint=r.crashPoint;out.seed=r.seed;out.points=r.points;out.winAmount=0;return out}
  r.status="CASED_OUT";r.cashedOut=true;r.cashoutMult=s.eff;r.win=Math.floor(r.betCost*s.eff);
  r.points=s.passed*this.cfg.points_per_line+(s.bon?this.cfg.points_xN_bonus:0)+this.cfg.points_cashout_bonus;
  const u=await this.user();u.bonusBalance+=r.win;LStore.set("ab_user",u);
  out.success=true;out.cashoutMultiplier=s.eff;out.winAmount=r.win;out.points=r.points;out.balance=u.bonusBalance;return out},
 async verify(id){const r=this.rounds[id];if(!r)throw new Error("Раунд недоступен");return {seed:r.seed,crashPoint:r.crashPoint,hash:r.hash}},
 pushHist(r){const h=LStore.get("ab_hist",[]);h.unshift({user:"Вы",theme:r.theme,bet:r.betCost,booster:r.boosterTier,coef:r.cashedOut?r.cashoutMult:r.crashPoint,result:r.cashedOut?"WIN":"LOSS",win:r.win,time:new Date().toISOString()});LStore.set("ab_hist",h.slice(0,50))},
 async sha(s){const b=await crypto.subtle.digest("SHA-256",enc.encode(s));return Array.from(new Uint8Array(b)).map(x=>x.toString(16).padStart(2,"0")).join("")},
 hex(n){const a=new Uint8Array(n);crypto.getRandomValues(a);return Array.from(a).map(x=>x.toString(16).padStart(2,"0")).join("")}
};

const BALLOON_SVG={
RED:'<svg viewBox="0 0 120 160" xmlns="http://www.w3.org/2000/svg"><path d="M60 6C30 6 12 30 12 58c0 30 26 52 48 66 22-14 48-36 48-66C108 30 90 6 60 6Z" fill="#e84545"/><path d="M38 20c-8 8-12 20-11 32" stroke="#ff9d9d" stroke-width="8" fill="none" stroke-linecap="round" opacity=".7"/><path d="M52 124h16l-4 14H56Z" fill="#b91f1f"/><rect x="48" y="138" width="24" height="18" rx="4" fill="#8a5a2b"/><path d="M52 138l-4-12M68 138l4-12" stroke="#6b4419" stroke-width="2"/></svg>',
GREEN:'<svg viewBox="0 0 120 160" xmlns="http://www.w3.org/2000/svg"><path d="M60 6C30 6 12 30 12 58c0 30 26 52 48 66 22-14 48-36 48-66C108 30 90 6 60 6Z" fill="#37b568"/><path d="M38 20c-8 8-12 20-11 32" stroke="#9be8b6" stroke-width="8" fill="none" stroke-linecap="round" opacity=".7"/><path d="M52 124h16l-4 14H56Z" fill="#2a8f4f"/><rect x="48" y="138" width="24" height="18" rx="4" fill="#8a5a2b"/><path d="M52 138l-4-12M68 138l4-12" stroke="#6b4419" stroke-width="2"/></svg>'
};

function fillRules(){const th=CFG.themes;
 $("rules-list").innerHTML=
 "<li>Выберите фрагмент пазла: ставка в бонусных баллах и бустер ×1–×4.</li>"+
 "<li>Шар летит вверх, коэффициент растёт. Точка краха определяется сервером до старта; хеш результата показан до полёта.</li>"+
 "<li>Кнопка «Забрать» доступна после 1-го уровня (×"+(1+CFG.level_step).toFixed(2)+"). Выигрыш = ставка × коэффициент.</li>"+
 "<li>Очки: за каждый уровень +"+CFG.points_per_line+", за cashout +"+CFG.points_cashout_bonus+", за активацию бустера +"+CFG.points_xN_bonus+".</li>"+
 "<li>Бустер ждёт на случайном уровне (вероятности в конфиге). Если шар достиг уровня бустера до cashout — коэффициент умножается на ×бустер.</li>"+
 "<li>Не успели забрать — ставка сгорает. Очки за пройденные уровни сохраняются.</li>"+
 "<li>После каждого раунда — случайный фрагмент коллекционного пазла.</li>"+
 "<li>Темы: красный шар — "+th.RED.levels+" уровней, зелёный — "+th.GREEN.levels+".</li>"+
 "<li>Без действий на экране результата — автоматический переход через "+CFG.idle_timeout_sec+" с.</li>";
}

async function buildBet(){
 USER=await Backend.user();
 $("bet-balance").textContent=USER.bonusBalance;
 $("bet-points").textContent=USER.points;
 $("bet-theme-label").textContent=(theme==="RED"?"🔴 Красный · 12 уровней":"🟢 Зелёный · 9 уровней");
 const box=$("puzzles");box.innerHTML="";
 CFG.bets.forEach((b,i)=>{
  const d=document.createElement("div");d.className="puzzle";
  const poor=USER.bonusBalance<b.cost;d.classList.toggle("poor",poor);
  d.innerHTML='<div class="cost">'+b.cost+'</div><div>бонусов</div><div class="boost">бустер ×'+b.booster+"</div>";
  d.onclick=()=>{if(poor){toast("Не хватает бонусов: нужно "+b.cost);return}
   betIndex=i;Array.prototype.forEach.call(box.children,c=>c.classList.remove("sel"));d.classList.add("sel");$("btn-start").disabled=false;tone(500,.06)};
  box.appendChild(d);
 });
 betIndex=-1;$("btn-start").disabled=true;
 const h=await Backend.history();const hh=$("history");hh.innerHTML="";
 h.slice(0,20).forEach(r=>{
  const row=document.createElement("div");row.className="hist-row";
  row.innerHTML="<span>"+esc(r.user)+" · "+(r.theme==="RED"?"🔴":"🟢")+" · ставка "+r.bet+" · буст ×"+r.booster+"</span><span>×"+Number(r.coef).toFixed(2)+' <span class="'+(r.result==="WIN"?"w":"l")+'">'+(r.result==="WIN"?"+"+r.win:"−"+r.bet)+"</span></span>";
  hh.appendChild(row);
 });
 if(!h.length)hh.innerHTML='<div class="muted">Пока пусто — станьте первым!</div>';
}

function goBet(t){if(t)theme=t;buildBet().catch(e=>toast(e.message));fillRules();show("scr-bet")}

async function showTheme(){
 USER=await Backend.user().catch(()=>USER);
 if(USER)$("theme-balance").textContent=USER.bonusBalance;
 show("scr-theme");
}

function buildLadder(){
 const th=CFG.themes[theme];const lad=$("ladder");lad.innerHTML="";
 const maxM=1+th.levels*CFG.level_step;
 for(let i=1;i<=th.levels;i++){
  const m=1+i*CFG.level_step;
  const line=document.createElement("div");line.className="ladd-line";
  line.style.bottom=(((m-1)/(maxM-1))*100)+"%";
  line.innerHTML="<span>Ур. "+i+" — ×"+m.toFixed(2)+"</span>";
  lad.appendChild(line);
 }
 if(round.boosterLevel>0){
  const m=1+round.boosterLevel*CFG.level_step;
  const ic=document.createElement("div");ic.className="boost-icon";
  ic.style.bottom=(((m-1)/(maxM-1))*100)+"%";
  ic.textContent="🎁 ×"+boostTier;
  lad.appendChild(ic);
 }
}

function popPoints(txt,level){
 const step=CFG.level_step;const th=CFG.themes[theme];const maxM=1+th.levels*step;
 const p=Math.max(0,Math.min(1,((1+level*step)-1)/(maxM-1)));
 const d=document.createElement("div");d.className="pt";d.textContent=txt;
 d.style.right="14px";d.style.bottom=(6+p*80)+"%";
 $("flight").appendChild(d);setTimeout(()=>d.remove(),1200);
}

function activateBoost(){
 boosterOn=true;
 $("boost-val").textContent=boostTier;
 $("boost-banner").classList.add("show");
 tone(392,.12,"square");setTimeout(()=>tone(784,.18,"square"),120);
 setTimeout(()=>$("boost-banner").classList.remove("show"),1600);
 popPoints("+"+CFG.points_xN_bonus+" буст!",Math.max(1,prevLevels));
}

function renderFlight(m,now){
 const th=CFG.themes[theme];
 const eff=boosterOn?Math.floor(m*boostTier*100)/100:m;
 const disp=boosterOn?eff:m;
 $("mult").textContent="×"+disp.toFixed(2);
 const step=CFG.level_step;
 $("mult").className=disp<1+step?"t1":disp<1+2*step?"t2":disp<1+3*step?"t3":"t4";
 const maxM=1+th.levels*step;
 const prog=Math.max(0,Math.min(1,(disp-1)/(maxM-1)));
 const bal=$("balloon");
 bal.style.bottom=(6+prog*80)+"%";
 bal.style.transform="rotate("+(Math.sin(now/300)*4)+"deg)";
 const passed=Math.min(th.levels,Math.floor((m-1)/step));
 if(passed>prevLevels){
  for(let i=prevLevels+1;i<=passed;i++){popPoints("+"+CFG.points_per_line,i);tone(620+i*30,.09)}
  prevLevels=passed;
  if(passed>=1&&!cashed)$("btn-cashout").disabled=false;
 }
 if(boosterOn)$("sub-mult").style.display="block",$("sub-mult").textContent="буст ×"+boostTier+" активен";
}

function tick(now){
 lastFrame=now;
 const el=(now-startMark)/1000;
 let m=Math.exp(CFG.multiplier_growth_rate*el);
 m=Math.floor(m*100)/100;if(m<1)m=1;
 if(polled&&Math.abs(m-serverMult)>0.15)m=serverMult;
 renderFlight(m,now);
 if(round.status==="CRASHED")return;
 raf=requestAnimationFrame(tick);
}

async function doPoll(){
 if(!round||round.dead)return;
 try{
  const st=await Backend.state(round.id);
  serverMult=st.multiplier;polled=true;
  round.status=st.status;
  if(st.boosterActivated&&!boosterOn)activateBoost();
  if(st.status==="CRASHED"&&!round.dead){round.dead=true;crash(st)}
 }catch(e){}
}

async function startRound(){
 if(betIndex<0)return;
 $("btn-start").disabled=true;
 try{
  const r=await Backend.start(theme,betIndex);
  USER.bonusBalance=r.balance;
  cashWin=0;cashMult=0;cashed=false;boosterOn=false;prevLevels=0;serverMult=1;polled=false;
  boostTier=CFG.bets[betIndex].booster;
  round={id:r.id,hash:r.hash,boosterLevel:r.boosterLevel||0,status:"FLYING",dead:false};
  startMark=performance.now();lastFrame=startMark;
  $("balloon").innerHTML=BALLOON_SVG[theme];
  $("balloon").style.display="block";$("balloon").style.bottom="6%";
  $("boom").style.display="none";
  $("mult").textContent="×1.00";$("mult").className="";
  $("sub-mult").style.display="none";
  $("boost-banner").classList.remove("show");
  $("hash-line").textContent="Хеш раунда (SHA-256): "+r.hash;
  buildLadder();
  const btn=$("btn-cashout");btn.disabled=true;btn.textContent="Забрать";
  show("scr-game");
  cancelAnimationFrame(raf);clearInterval(poll);
  raf=requestAnimationFrame(tick);
  poll=setInterval(doPoll,250);
  if(!onboardDone){onboardDone=true;localStorage.setItem("ab_onboard","1");const ob=$("onboard");ob.style.display="block";setTimeout(()=>ob.style.display="none",4000)}
  toast("Списано "+CFG.bets[betIndex].cost+" бонусов. Удачи!");
 }catch(e){toast("Ошибка: "+e.message);$("btn-start").disabled=false}
}

async function doCashout(){
 if(cashed||!round||round.dead)return;
 const btn=$("btn-cashout");btn.disabled=true;
 try{
  const r=await Backend.cashout(round.id);
  if(r.success){
   cashed=true;cashWin=r.winAmount;cashMult=r.cashoutMultiplier;
   btn.textContent="Забрано ✓ ×"+cashMult.toFixed(2);
   toast("Забрано! Могли бы забрать больше…");
   tone(523,.1);setTimeout(()=>tone(784,.16),110);
   USER.bonusBalance=r.balance;
   FX.burst();
  }else{
   round.dead=true;crash({crashPoint:r.crashPoint,seed:r.seed,points:r.points,winAmount:0,status:"CRASHED"});
  }
 }catch(e){btn.disabled=false;toast(e.message)}
}

function crash(st){
 cancelAnimationFrame(raf);clearInterval(poll);
 document.body.classList.add("shake");
 const bal=$("balloon");bal.style.display="none";
 const boom=$("boom");boom.style.display="block";
 boom.style.left=bal.style.left;boom.style.bottom=bal.style.bottom;
 tone(110,.35,"sawtooth",.09);
 setTimeout(()=>{document.body.classList.remove("shake");showResult(st)},1100);
}

async function showResult(st){
 const win=cashed?cashWin:(st.winAmount||0);
 const pts=st.points||0;
 $("res-title").textContent=cashed?"🎉 Вы забрали выигрыш!":"💥 Шар лопнул!";
 const am=$("res-amount");
 if(cashed){am.className="res-win";am.textContent="+"+win+" бонусов"}
 else{am.className="res-lose";am.textContent="Ставка сгорела (−"+(betIndex>=0?CFG.bets[betIndex].cost:0)+")"}
 $("res-mult").textContent=cashed?("Коэффициент cashout: ×"+Number(cashMult).toFixed(2)):("Коэффициент краха: ×"+Number(st.crashPoint).toFixed(2));
 $("res-more").textContent="Могли бы забрать больше: максимум был ×"+Number(st.crashPoint).toFixed(2);
 USER=await Backend.user().catch(()=>USER);
 $("res-points").textContent="+"+pts;
 $("res-total-points").textContent=USER?USER.points:"—";
 const frag=1+Math.floor(Math.random()*8);
 const key="ab_frag_"+frag;const cnt=parseInt(localStorage.getItem(key)||"0",10)+1;localStorage.setItem(key,String(cnt));
 $("res-reward").textContent="🧩 Фрагмент пазла №"+frag+" (в коллекции: "+cnt+")";
 $("res-fair").textContent="Честность: до полёта опубликован хеш "+round.hash.slice(0,24)+"…";
 const rv=$("res-verify");rv.innerHTML="";
 const vb=document.createElement("button");vb.className="btn-ghost";vb.style.marginTop="8px";vb.textContent="Проверить seed";
 vb.onclick=async()=>{
  try{
   const v=await Backend.verify(round.id);
   const h=await sha256Hex(v.seed+"|"+round.id+"|"+Number(v.crashPoint).toFixed(2));
   rv.firstChild.textContent=h===round.hash?"✅ Проверено: хеш совпал — результат был определён до полёта":"❌ Хеш не совпал";
  }catch(e){toast("Проверка недоступна: "+e.message)}
 };
 rv.appendChild(vb);
 show("scr-result");
 if(cashed)FX.win();
 startIdle();
}


const FX={cv:null,ctx:null,parts:[],running:false,last:0,
 init(){if(this.cv)return;this.cv=$("fx");this.ctx=this.cv.getContext("2d");const rs=()=>{this.cv.width=innerWidth;this.cv.height=innerHeight};addEventListener("resize",rs);rs()},
 coin(x,y,vx,vy){this.parts.push({t:"c",x:x,y:y,vx:vx,vy:vy,r:8+Math.random()*8,rot:Math.random()*6.28,vr:(Math.random()-.5)*.3,a:1})},
 fw(x,y,color){const n=50+Math.floor(Math.random()*30);for(let i=0;i<n;i++){const an=Math.random()*6.28,sp=1.5+Math.random()*3.5;this.parts.push({t:"p",x:x,y:y,vx:Math.cos(an)*sp,vy:Math.sin(an)*sp,color:color,a:1,d:0.011+Math.random()*0.011})}},
 rocket(x,y,color){this.parts.push({t:"r",x:x,y:innerHeight+10,vx:0,vy:-(7+Math.random()*4),ty:y,color:color,a:1})},
 win(){this.init();const cols=["#ffd54a","#ff6b6b","#6bffb8","#6bb8ff","#ff9de2"];
  for(let i=0;i<5;i++){setTimeout(()=>this.rocket(innerWidth*(0.15+Math.random()*0.7),innerHeight*(0.15+Math.random()*0.35),cols[Math.floor(Math.random()*cols.length)]),i*380)}
  for(let i=0;i<70;i++){setTimeout(()=>this.coin(Math.random()*innerWidth,-20-Math.random()*140,(Math.random()-.5)*1.6,2+Math.random()*3),Math.random()*2000)}
  this.run(5600)},
 burst(){this.init();const r=$("btn-cashout").getBoundingClientRect();
  for(let i=0;i<28;i++){const an=-1.5708+(Math.random()-.5)*1.7,sp=4+Math.random()*5;this.coin(r.left+r.width/2,r.top+r.height/2,Math.cos(an)*sp,Math.sin(an)*sp)}
  this.run(2400)},
 run(ms){if(!this.running){this.running=true;this.last=performance.now();requestAnimationFrame(this.step.bind(this))}clearTimeout(this._stop);this._stop=setTimeout(()=>{this.running=false},ms)},
 step(now){const c=this.ctx;c.clearRect(0,0,this.cv.width,this.cv.height);this.last=now;
  this.parts=this.parts.filter(p=>p.a>0&&p.y<innerHeight+80);
  for(const p of this.parts){
   if(p.t==="r"){p.y+=p.vy;p.vy+=0.12;if(p.y<=p.ty||p.vy>-1){this.fw(p.x,p.y,p.color);p.a=0}this.draw(p,c)}
   else if(p.t==="p"){p.x+=p.vx;p.y+=p.vy;p.vy=p.vy*0.985+0.045;p.vx*=0.985;p.a-=p.d;this.draw(p,c)}
   else{p.x+=p.vx;p.y+=p.vy;p.vy+=0.12;p.vx*=0.995;p.rot+=p.vr;if(p.y>innerHeight-14&&p.vy>0){p.vy*=-0.35;p.vx*=0.6}p.a-=0.0045;this.draw(p,c)}
  }
  if(this.running||this.parts.length)requestAnimationFrame(this.step.bind(this));else c.clearRect(0,0,this.cv.width,this.cv.height)},
 draw(p,c){
  if(p.t==="r"){c.globalAlpha=1;c.fillStyle=p.color;c.beginPath();c.arc(p.x,p.y,2.4,0,6.29);c.fill();return}
  if(p.t==="p"){c.globalAlpha=Math.max(0,p.a);c.fillStyle=p.color;c.beginPath();c.arc(p.x,p.y,2.2,0,6.29);c.fill();return}
  c.globalAlpha=Math.max(0,Math.min(1,p.a));c.save();c.translate(p.x,p.y);c.rotate(p.rot);c.scale(1,0.25+0.75*Math.abs(Math.sin(p.rot*2)));
  const g=c.createRadialGradient(0,0,1,0,0,p.r);g.addColorStop(0,"#fff3b0");g.addColorStop(0.6,"#ffd54a");g.addColorStop(1,"#c98a00");
  c.fillStyle=g;c.beginPath();c.arc(0,0,p.r,0,6.29);c.fill();c.strokeStyle="#8a5a00";c.lineWidth=1.5;c.stroke();
  c.fillStyle="rgba(255,255,255,.7)";c.beginPath();c.arc(-p.r*0.3,-p.r*0.3,p.r*0.22,0,6.29);c.fill();c.restore();c.globalAlpha=1}
};

function startIdle(){
 stopIdle();
 let n=(CFG&&CFG.idle_timeout_sec)||10;
 $("res-timer").textContent="Переход к темам через "+n+" с…";
 idleInt=setInterval(()=>{n--;if(n<=0){stopIdle();showTheme()}else $("res-timer").textContent="Переход к темам через "+n+" с…"},1000);
 document.body.addEventListener("pointerdown",resetIdle);
}
function resetIdle(){if($("scr-result").classList.contains("active"))startIdle()}
function stopIdle(){clearInterval(idleInt);document.body.removeEventListener("pointerdown",resetIdle)}

async function openConfig(){
 try{$("cfg-json").value=JSON.stringify(await Backend.config(),null,2);$("modal-config").classList.add("open")}
 catch(e){toast(e.message)}
}
async function saveConfig(){
 try{
  const c=JSON.parse($("cfg-json").value);
  CFG=await Backend.saveConfig(c);
  $("modal-config").classList.remove("open");
  toast("Сохранено! Применится со следующего раунда");
 }catch(e){toast("Ошибка: "+e.message)}
}

function bind(){
 document.querySelectorAll(".balloon-pick").forEach(b=>b.onclick=()=>{tone(700,.08);goBet(b.dataset.theme)});
 $("sw-red").onclick=()=>{theme="RED";goBet()};
 $("sw-green").onclick=()=>{theme="GREEN";goBet()};
 $("btn-start").onclick=startRound;
 $("btn-cashout").onclick=doCashout;
 $("btn-again").onclick=()=>goBet();
 $("btn-to-theme").onclick=showTheme;
 $("btn-back-theme").onclick=showTheme;
 $("btn-rules").onclick=()=>$("modal-rules").classList.add("open");
 $("btn-config").onclick=openConfig;
 $("btn-refill").onclick=async()=>{try{USER=await Backend.addBalance(100);toast("+100 бонусов");goBet()}catch(e){toast(e.message)}};
 $("btn-cfg-save").onclick=saveConfig;
 document.querySelectorAll("[data-close]").forEach(b=>b.onclick=()=>$(b.dataset.close).classList.remove("open"));
 document.querySelectorAll(".modal").forEach(m=>m.addEventListener("click",e=>{if(e.target===m)m.classList.remove("open")}));
}

async function boot(){
 bind();
 document.querySelectorAll("[data-bsvg]").forEach(d=>d.innerHTML=BALLOON_SVG[d.dataset.bsvg]);
 Backend=ServerBackend;
 try{
  CFG=await Backend.config();USER=await Backend.user();
 }catch(e){
  Backend=LocalBackend;
  try{CFG=await Backend.config();USER=await Backend.user();toast("Сервер недоступен — локальный режим (математика в браузере)")}
  catch(e2){toast("Ошибка инициализации: "+e2.message)}
 }
 showTheme();
}
boot();
</script>
</body>
</html>

""";

  // ------------------------- модель -------------------------

  static class User {
    String id, name, email, passHash, token;
    boolean admin;
    long bonusBalance = 500, points = 0, tickets = 0, rounds = 0, fragments = 0, lastPrize = 0, dayPoints = 0;
    String dayStamp = "";
    Map<String, Object> toMap() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", id); m.put("name", name); m.put("email", email); m.put("admin", admin);
      m.put("bonusBalance", bonusBalance); m.put("points", points); m.put("tickets", tickets); m.put("rounds", rounds);
      m.put("fragments", fragments); m.put("lastPrize", lastPrize); m.put("dayPoints", dayPoints);
      return m;
    }
  }

  static class Round {
    String id, userId, theme, status = "FLYING", seed, hash, startedAt, finishedAt;
    long betCost, win, points, fragments;
    int boosterTier, boosterLevel;
    double crashPoint, cashoutMult, finalMult;
    long startNanos;
    boolean cashedOut;
  }

  static class ApiError extends RuntimeException {
    final int code;
    ApiError(int code, String msg) { super(msg); this.code = code; }
  }

  // ------------------------- утилиты -------------------------

  @SuppressWarnings("unchecked")
  static void deepMerge(Map<String, Object> base, Map<String, Object> over) {
    for (Map.Entry<String, Object> e : over.entrySet()) {
      Object bv = base.get(e.getKey());
      if (bv instanceof Map && e.getValue() instanceof Map) deepMerge((Map<String, Object>) bv, (Map<String, Object>) e.getValue());
      else base.put(e.getKey(), e.getValue());
    }
  }

  static double gd(Map<String, Object> m, String k, double d) {
    Object v = m.get(k);
    return v instanceof Number ? ((Number) v).doubleValue() : d;
  }

  static int gi(Map<String, Object> m, String k, int d) {
    Object v = m.get(k);
    return v instanceof Number ? ((Number) v).intValue() : d;
  }

  static String fmt2(double v) { return String.format(Locale.US, "%.2f", v); }

  static String hex(byte[] b) {
    StringBuilder s = new StringBuilder();
    for (byte x : b) s.append(String.format(Locale.US, "%02x", x & 0xFF));
    return s.toString();
  }

  static byte[] sha256bytes(String s) {
    try { return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)); }
    catch (Exception e) { throw new RuntimeException(e); }
  }

  static String sha256(String s) { return hex(sha256bytes(s)); }

  static String devSeed() {
    Object s = CFG.get("dev_seed");
    return s instanceof String ? (String) s : null;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> themeCfg(String theme) {
    Map<String, Object> themes = (Map<String, Object>) CFG.get("themes");
    Map<String, Object> th = themes == null ? null : (Map<String, Object>) themes.get(theme);
    if (th == null) throw new ApiError(400, "Неизвестная тема: " + theme);
    return th;
  }

  /** Точка краха: (1 + Exp(1)/alpha) * edge, кламп [min, max]; за пределами max — мгновенный крах. */
  static double genCrash(Round r) {
    double u;
    if (devSeed() != null) {
      byte[] h = sha256bytes(devSeed() + "|" + r.id);
      long v = 0;
      for (int i = 0; i < 8; i++) v = (v << 8) | (h[i] & 0xFFL);
      u = (v & Long.MAX_VALUE) / (double) Long.MAX_VALUE;
      if (u < 1e-9) u = 1e-9;
    } else {
      u = RNG.nextDouble();
    }
    double e = -Math.log(1.0 - u);
    double alpha = gd(CFG, "alpha", 1.2), edge = gd(CFG, "edge", 0.95);
    double min = gd(CFG, "min_crash_multiplier", 1.0), max = gd(CFG, "max_multiplier", 50.0);
    double crash = (1.0 + e / alpha) * edge;
    if (crash > max) crash = min; // редкий мгновенный крах
    if (crash < min) crash = min;
    return Math.round(crash * 100.0) / 100.0;
  }

  /** Позиция бустера: дискретное распределение по line_loot_prob (сумма нормализуется). */
  @SuppressWarnings("unchecked")
  static int pickBoosterLevel(String theme) {
    List<Object> probs = (List<Object>) themeCfg(theme).get("line_loot_prob");
    double sum = 0;
    for (Object p : probs) sum += ((Number) p).doubleValue();
    double u = RNG.nextDouble() * sum, acc = 0;
    for (int i = 0; i < probs.size(); i++) {
      acc += ((Number) probs.get(i)).doubleValue();
      if (u < acc) return i + 1;
    }
    return probs.size();
  }

  static int ppl() { return gi(CFG, "points_per_line", 10); }

  static void addPoints(User u, long pts) {
    String today = java.time.LocalDate.now().toString();
    if (!today.equals(u.dayStamp)) { u.dayStamp = today; u.dayPoints = 0; }
    u.points += pts;
    u.dayPoints += pts;
  }

  static long fragsFor(long bet) {
    long rate = (long) gd(CFG, "puzzle_rate", 10);
    return Math.max(1, bet / Math.max(1, rate));
  }
  static int pcb() { return gi(CFG, "points_cashout_bonus", 25); }
  static int pxb() { return gi(CFG, "points_xN_bonus", 20); }

  /** Коэффициент растёт как m(t)=1+growth*t^p (p=growth_curve>1 — плавный старт, разгон к середине), дискретизация 0.01. */
  static double multAt(long startNanos, long now) {
    double growth = gd(CFG, "multiplier_growth_rate", 0.42);
    double elapsed = (now - startNanos) / 1_000_000_000.0;
    double p = gd(CFG, "growth_curve", 1.8);
    double m = 1.0 + growth * Math.pow(elapsed, p);
    m = Math.floor(m * 100.0) / 100.0;
    return Math.max(1.0, m);
  }

  static int levelsPassed(double mult, int levels) {
    double step = gd(CFG, "level_step", 0.5);
    int p = (int) Math.floor((mult - 1.0) / step);
    return Math.max(0, Math.min(levels, p));
  }

  static void pushHistory(Round r) {
    User u = USERS.get(r.userId);
    Map<String, Object> h = new LinkedHashMap<>();
    h.put("user", u == null ? "?" : u.name);
    h.put("theme", r.theme);
    h.put("bet", r.betCost);
    h.put("booster", r.boosterTier);
    h.put("coef", r.cashedOut ? r.cashoutMult : r.crashPoint);
    h.put("crash", r.crashPoint);
    h.put("result", r.cashedOut ? "WIN" : "LOSS");
    h.put("win", r.win);
    h.put("time", r.finishedAt);
    HISTORY.add(0, h);
    while (HISTORY.size() > 100) HISTORY.remove(HISTORY.size() - 1);
  }

  static void gcRounds() {
    if (ROUNDS.size() < 300) return;
    long cutoff = System.nanoTime() - 3_600_000_000_000L;
    ROUNDS.values().removeIf(r -> r.startNanos < cutoff);
  }

  // ------------------------- игровые операции -------------------------

  @SuppressWarnings("unchecked")
  static synchronized Map<String, Object> startRound(User u, String theme, long amount, int booster) {
    if (!Boolean.TRUE.equals(CFG.get("is_active"))) throw new ApiError(403, "Игра выключена конфигурацией");
    themeCfg(theme); // валидация темы
    long minBet = (long) gd(CFG, "min_bet", 1);
    if (amount < minBet) throw new ApiError(400, "Минимальная ставка: " + minBet + " бонусов");
    List<Object> bl = (List<Object>) CFG.get("boosters");
    boolean boosterOk = false;
    if (bl != null) for (Object o : bl) if (((Number) o).intValue() == booster) boosterOk = true;
    if (!boosterOk) throw new ApiError(400, "Такого бустера нет в конфигурации");
    if (u.bonusBalance < amount) throw new ApiError(400, "Не хватает бонусов");
    u.bonusBalance -= amount;
    saveUsers();

    Round r = new Round();
    r.id = "R" + Long.toHexString(RNG.nextLong() & 0xFFFFFFFFFFFFL);
    r.userId = u.id;
    r.theme = theme;
    r.betCost = amount;
    r.boosterTier = booster;
    r.crashPoint = genCrash(r);
    r.boosterLevel = pickBoosterLevel(theme);
    r.seed = hex(randomBytes(16));
    r.hash = sha256(r.seed + "|" + r.id + "|" + fmt2(r.crashPoint));
    r.startNanos = System.nanoTime();
    r.startedAt = Instant.now().toString();
    ROUNDS.put(r.id, r);
    gcRounds();

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", r.id);
    out.put("hash", r.hash);
    out.put("boosterLevel", r.boosterLevel);
    out.put("balance", u.bonusBalance);
    return out;
  }

  static byte[] randomBytes(int n) {
    byte[] b = new byte[n];
    RNG.nextBytes(b);
    return b;
  }

  /** Открытие подарка: списание пазлов, взвешенный случайный приз из конфига. */
  @SuppressWarnings("unchecked")
  static synchronized Map<String, Object> openGift(User u) {
    long need = (long) gd(CFG, "gift_threshold", 100);
    if (u.fragments < need) throw new ApiError(400, "Не хватает пазлов: " + u.fragments + "/" + need);
    List<Object> prizes = (List<Object>) CFG.get("gift_prizes");
    if (prizes == null || prizes.isEmpty()) throw new ApiError(500, "Призы не настроены в конфиге");
    double total = 0;
    for (Object o : prizes) total += ((Number) ((Map<String, Object>) o).get("weight")).doubleValue();
    double x = RNG.nextDouble() * total;
    long amount = 0;
    for (Object o : prizes) {
      Map<String, Object> p = (Map<String, Object>) o;
      x -= ((Number) p.get("weight")).doubleValue();
      if (x < 0) { amount = ((Number) p.get("amount")).longValue(); break; }
    }
    if (amount == 0) amount = ((Number) ((Map<String, Object>) prizes.get(prizes.size() - 1)).get("amount")).longValue();
    u.fragments -= need;
    u.lastPrize = amount;
    saveUsers();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("prize", amount);
    out.put("fragments", u.fragments);
    return out;
  }

  @SuppressWarnings("unchecked")
  static void loadLeaders() {
    try {
      if (Files.exists(LEADERS_PATH)) {
        Map<String, Object> m = (Map<String, Object>) Json.parse(Files.readString(LEADERS_PATH, StandardCharsets.UTF_8));
        Object d = m.get("day");
        leaderDay = d == null ? "" : String.valueOf(d);
        Object s = m.get("snapshots");
        if (s instanceof List) LEADER_SNAPS.addAll((List<Map<String, Object>>) s);
      }
    } catch (Exception ignored) { }
    if (leaderDay.isEmpty()) leaderDay = java.time.LocalDate.now().toString();
  }

  static void saveLeaders() {
    try {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("day", leaderDay);
      m.put("snapshots", LEADER_SNAPS);
      Files.writeString(LEADERS_PATH, Json.stringify(m), StandardCharsets.UTF_8);
    } catch (Exception e) { System.out.println("leaders.json не сохранён: " + e); }
  }

  static List<Map<String, Object>> topPlayers(boolean daily, String day) {
    List<Map<String, Object>> arr = new ArrayList<>();
    USERS.values().stream()
      .filter(u -> !daily || day.equals(u.dayStamp))
      .sorted((a, b) -> Long.compare(daily ? b.dayPoints : b.points, daily ? a.dayPoints : a.points))
      .limit(10)
      .forEach(u -> {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", u.id);
        row.put("name", u.name != null && !u.name.isEmpty() ? u.name : maskEmail(u.email));
        row.put("points", daily ? u.dayPoints : u.points);
        row.put("balance", u.bonusBalance);
        row.put("rounds", u.rounds);
        arr.add(row);
      });
    return arr;
  }

  static String maskEmail(String e) {
    int at = e.indexOf('@');
    if (at <= 1) return e.substring(0, 1) + "***";
    return e.substring(0, 1) + "***" + e.substring(at);
  }

  static Round require(String id) {
    Round r = id == null ? null : ROUNDS.get(id);
    if (r == null) throw new ApiError(404, "Раунд не найден");
    return r;
  }

  /** Авторитетное состояние раунда; при достижении краха — финализация и раскрытие seed. */
  static synchronized Map<String, Object> roundState(Round r, long now) {
    int levels = ((Number) themeCfg(r.theme).get("levels")).intValue();
    double mult = multAt(r.startNanos, now);
    int passed = levelsPassed(mult, levels);
    boolean boostOn = r.boosterLevel > 0 && passed >= r.boosterLevel;
    double eff = boostOn ? Math.floor(mult * r.boosterTier * 100.0) / 100.0 : mult;
    boolean crashedNow = mult >= r.crashPoint;

    if (crashedNow && !r.status.equals("CRASHED")) {
      r.status = "CRASHED";
      r.finishedAt = Instant.now().toString();
      r.finalMult = r.crashPoint;
      if (!r.cashedOut) {
        r.win = 0;
        r.points = (long) passed * ppl() + (boostOn ? pxb() : 0);
        User fu2 = USERS.get(r.userId);
        addPoints(fu2, r.points);
        r.fragments = fragsFor(r.betCost);
        fu2.fragments += r.fragments;
      }
      User fu = USERS.get(r.userId);
      fu.rounds++;
      saveUsers();
      pushHistory(r);
    }

    Map<String, Object> st = new LinkedHashMap<>();
    st.put("id", r.id);
    st.put("status", r.status);
    st.put("multiplier", mult);
    st.put("effMultiplier", eff);
    st.put("levelsPassed", passed);
    st.put("boosterActivated", boostOn);
    st.put("maxLevels", levels);
    st.put("hash", r.hash);
    if (r.cashedOut) st.put("cashoutMultiplier", r.cashoutMult);
    if (r.status.equals("CRASHED")) {
      st.put("crashPoint", r.crashPoint);
      st.put("seed", r.seed);
      st.put("points", r.points);
      st.put("winAmount", r.win);
      st.put("fragments", r.fragments);
    }
    return st;
  }

  static synchronized Map<String, Object> cashout(User who, String id) {
    Round r = require(id);
    if (!r.userId.equals(who.id)) throw new ApiError(403, "Чужой раунд");
    if (r.status.equals("CRASHED")) throw new ApiError(409, "Раунд уже завершён");
    if (r.status.equals("CASED_OUT")) throw new ApiError(409, "Выигрыш уже забран");
    User u = USERS.get(r.userId);
    long now = System.nanoTime();
    int levels = ((Number) themeCfg(r.theme).get("levels")).intValue();
    double mult = multAt(r.startNanos, now);
    int passed = levelsPassed(mult, levels);
    boolean boostOn = r.boosterLevel > 0 && passed >= r.boosterLevel;
    double eff = boostOn ? Math.floor(mult * r.boosterTier * 100.0) / 100.0 : mult;

    Map<String, Object> out = new LinkedHashMap<>();
    if (mult >= r.crashPoint) {
      // игрок не успел — крах фиксируем немедленно
      r.status = "CRASHED";
      r.cashedOut = false;
      r.win = 0;
      r.points = (long) passed * ppl() + (boostOn ? pxb() : 0);
      r.fragments = fragsFor(r.betCost);
      r.finishedAt = Instant.now().toString();
      addPoints(u, r.points);
      u.fragments += r.fragments;
      u.rounds++;
      saveUsers();
      pushHistory(r);
      out.put("success", false);
      out.put("status", "CRASHED");
      out.put("crashPoint", r.crashPoint);
      out.put("seed", r.seed);
      out.put("points", r.points);
      out.put("winAmount", 0);
      out.put("fragments", r.fragments);
      return out;
    }
    r.status = "CASED_OUT";
    r.cashedOut = true;
    r.cashoutMult = eff;
    r.finalMult = eff;
    r.win = (long) Math.floor(r.betCost * eff);
    r.points = (long) passed * ppl() + (boostOn ? pxb() : 0) + pcb();
    addPoints(u, r.points);
    r.fragments = fragsFor(r.betCost);
    u.fragments += r.fragments;
    u.bonusBalance += r.win;
    u.rounds++;
    saveUsers();
    out.put("success", true);
    out.put("cashoutMultiplier", eff);
    out.put("winAmount", r.win);
    out.put("points", r.points);
    out.put("fragments", r.fragments);
    out.put("balance", u.bonusBalance);
    return out;
  }

  // ------------------------- пользователи и сессии -------------------------

  static User newUser(String email, String pass, boolean admin) {
    User u = new User();
    u.id = "u" + hex(randomBytes(6));
    u.email = email;
    u.name = admin ? "Администратор" : email;
    u.passHash = sha256(pass);
    u.admin = admin;
    u.bonusBalance = (long) gd(CFG, "starting_balance", 500);
    USERS.put(u.id, u);
    BY_EMAIL.put(email, u.id);
    return u;
  }

  static Map<String, Object> session(User u) {
    String token = hex(randomBytes(16));
    TOKENS.put(token, u.id);
    u.token = token;
    saveUsers();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("token", token);
    out.put("admin", u.admin);
    out.put("user", u.toMap());
    return out;
  }

  static synchronized Map<String, Object> register(Map<String, Object> b) {
    String email = String.valueOf(b.get("email")).trim().toLowerCase(Locale.US);
    String pass = String.valueOf(b.get("password"));
    if (email.length() < 5 || email.indexOf('@') < 1 || email.indexOf('.', email.indexOf('@')) < 0)
      throw new ApiError(400, "Некорректный email");
    if (pass.length() < 4) throw new ApiError(400, "Пароль минимум 4 символа");
    if ("admin".equals(email) || "admin@mail.com".equals(email)) throw new ApiError(409, "Этот логин зарезервирован");
    if (BY_EMAIL.containsKey(email)) throw new ApiError(409, "Такой email уже зарегистрирован");
    User u = newUser(email, pass, false);
    saveUsers();
    return session(u);
  }

  static Map<String, Object> login(Map<String, Object> b) {
    String email = String.valueOf(b.get("email")).trim().toLowerCase(Locale.US);
    String pass = String.valueOf(b.get("password"));
    // Жёсткое исключение: admin@mail.com/12345678 (или admin/12345678) входит ВСЕГДА.
    if (("admin@mail.com".equals(email) || "admin".equals(email)) && "12345678".equals(pass)) {
      String id = BY_EMAIL.get("admin@mail.com");
      if (id == null) id = BY_EMAIL.get("admin");
      User u = id == null ? null : USERS.get(id);
      if (u == null) u = newUser("admin@mail.com", "12345678", true);
      else {
        if (!"admin@mail.com".equals(u.email)) {
          BY_EMAIL.remove(u.email);
          u.email = "admin@mail.com";
          u.name = "Администратор";
          BY_EMAIL.put(u.email, u.id);
        }
        if (!u.admin || !u.passHash.equals(sha256("12345678"))) {
          u.admin = true;
          u.passHash = sha256("12345678");
        }
        saveUsers();
      }
      return session(u);
    }
    String uid = BY_EMAIL.get(email);
    User u = uid == null ? null : USERS.get(uid);
    if (u == null || !u.passHash.equals(sha256(pass)))
      throw new ApiError(401, "Неверный email или пароль");
    return session(u);
  }

  static User auth(HttpExchange ex) {
    String h = ex.getRequestHeaders().getFirst("Authorization");
    if (h != null && h.startsWith("Bearer ")) {
      String id = TOKENS.get(h.substring(7));
      User u = id == null ? null : USERS.get(id);
      if (u != null) return u;
    }
    throw new ApiError(401, "Требуется вход");
  }

  static User requireAdmin(HttpExchange ex) {
    User u = auth(ex);
    if (!u.admin) throw new ApiError(403, "Нет доступа: нужен вход администратора");
    return u;
  }

  @SuppressWarnings("unchecked")
  static void loadUsers() {
    try {
      if (Files.exists(USERS_PATH)) {
        for (Object o : (List<Object>) Json.parse(Files.readString(USERS_PATH, StandardCharsets.UTF_8))) {
          Map<String, Object> m = (Map<String, Object>) o;
          User u = new User();
          u.id = String.valueOf(m.get("id"));
          u.email = String.valueOf(m.get("email"));
          u.name = String.valueOf(m.get("name"));
          u.passHash = String.valueOf(m.get("passHash"));
          u.admin = Boolean.TRUE.equals(m.get("admin"));
          u.bonusBalance = ((Number) m.get("bonusBalance")).longValue();
          u.points = ((Number) m.get("points")).longValue();
          u.tickets = ((Number) m.get("tickets")).longValue();
          u.rounds = ((Number) m.get("rounds")).longValue();
          u.fragments = m.get("fragments") instanceof Number ? ((Number) m.get("fragments")).longValue() : 0;
          u.lastPrize = m.get("lastPrize") instanceof Number ? ((Number) m.get("lastPrize")).longValue() : 0;
          u.dayPoints = m.get("dayPoints") instanceof Number ? ((Number) m.get("dayPoints")).longValue() : 0;
          u.dayStamp = m.get("dayStamp") instanceof String ? (String) m.get("dayStamp") : "";
          USERS.put(u.id, u);
          BY_EMAIL.put(u.email, u.id);
          Object tk = m.get("token");
          if (tk != null && !String.valueOf(tk).isEmpty()) TOKENS.put(String.valueOf(tk), u.id);
        }
      }
    } catch (Exception e) {
      System.out.println("users.json не прочитан: " + e);
    }
    if (!BY_EMAIL.containsKey("admin@mail.com")) {
      String oldId = BY_EMAIL.get("admin");
      if (oldId != null) {
        User u = USERS.get(oldId);
        BY_EMAIL.remove("admin");
        u.email = "admin@mail.com";
        u.name = "Администратор";
        u.admin = true;
        BY_EMAIL.put(u.email, u.id);
        saveUsers();
      } else newUser("admin@mail.com", "12345678", true);
    }
  }

  static void saveUsers() {
    try {
      List<Map<String, Object>> arr = new ArrayList<>();
      for (User u : USERS.values()) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.id); m.put("email", u.email); m.put("name", u.name);
        m.put("passHash", u.passHash); m.put("admin", u.admin);
        m.put("token", u.token == null ? "" : u.token);
        m.put("bonusBalance", u.bonusBalance); m.put("points", u.points);
        m.put("tickets", u.tickets); m.put("rounds", u.rounds);
        m.put("fragments", u.fragments); m.put("lastPrize", u.lastPrize);
        m.put("dayPoints", u.dayPoints); m.put("dayStamp", u.dayStamp);
        arr.add(m);
      }
      Files.writeString(USERS_PATH, Json.stringify(arr), StandardCharsets.UTF_8);
    } catch (Exception e) {
      System.out.println("Не удалось сохранить users.json: " + e);
    }
  }

  /** Проверка: какие ассеты из config.json реально существуют на диске. */
  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> assetsReport() {
    List<Map<String, Object>> out = new ArrayList<>();
    checkAssets("", CFG.get("assets"), out);
    return out;
  }

  @SuppressWarnings("unchecked")
  static void checkAssets(String key, Object o, List<Map<String, Object>> out) {
    if (o instanceof String) {
      String p = (String) o;
      if (p.isEmpty()) return;
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("asset", key);
      m.put("path", p);
      try {
        Path f = Path.of(p);
        boolean ok = Files.isRegularFile(f);
        m.put("exists", ok);
        m.put("bytes", ok ? Files.size(f) : 0);
      } catch (Exception e) {
        m.put("exists", false);
        m.put("bytes", 0);
      }
      out.add(m);
    } else if (o instanceof Map) {
      for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet())
        checkAssets(key.isEmpty() ? e.getKey() : key + "." + e.getKey(), e.getValue(), out);
    } else if (o instanceof List) {
      int i = 0;
      for (Object x : (List<Object>) o) checkAssets(key + "[" + (i++) + "]", x, out);
    }
  }

  /** Автообнаружение ассетов: файлы в assets/ с конвенциональными именами. */
  static Map<String, Object> discoverAssets() {
    Map<String, String> files = new TreeMap<>();
    Path dir = Path.of("assets");
    if (Files.isDirectory(dir)) {
      try (var st = Files.list(dir)) {
        for (Path f : (Iterable<Path>) st.filter(Files::isRegularFile)::iterator) {
          String name = f.getFileName().toString();
          int dot = name.lastIndexOf('.');
          if (dot <= 0) continue;
          files.putIfAbsent(name.substring(0, dot).toLowerCase(Locale.US), "assets/" + name);
        }
      } catch (Exception ignored) { }
    }
    Map<String, Object> out = new LinkedHashMap<>();
    Map<String, Object> balloons = new LinkedHashMap<>();
    balloons.put("RED", files.get("balloon-red"));
    balloons.put("GREEN", files.get("balloon-green"));
    out.put("balloons", balloons);
    out.put("launchpad", files.get("launchpad"));
    out.put("clouds", pickNumbered(files, "cloud"));
    out.put("birds", pickNumbered(files, "bird"));
    Map<String, Object> icons = new LinkedHashMap<>();
    icons.put("balance", files.get("icon-balance"));
    icons.put("points", files.get("icon-points"));
    icons.put("rules", files.get("icon-rules"));
    icons.put("reward", files.get("icon-reward"));
    icons.put("red", files.get("icon-red"));
    icons.put("green", files.get("icon-green"));
    icons.put("swgreen", files.get("icon-sw-green"));
    icons.put("swred", files.get("icon-sw-red"));
    icons.put("balloon", files.get("icon-balloon"));
    out.put("icons", icons);
    Map<String, Object> sounds = new LinkedHashMap<>();
    for (String s : new String[]{"theme", "click", "level", "boost", "cashout", "crash", "flight", "ambient"})
      sounds.put(s, files.get(s));
    out.put("sounds", sounds);
    Map<String, Object> fonts = new LinkedHashMap<>();
    fonts.put("regular", files.get("font-regular"));
    fonts.put("bold", files.get("font-bold"));
    out.put("fonts", fonts);
    Map<String, Object> sky = new LinkedHashMap<>();
    sky.put("low", files.get("sky-low"));
    sky.put("mid", files.get("sky-mid"));
    sky.put("high", files.get("sky-high"));
    sky.put("galaxy", files.get("sky-galaxy"));
    Map<String, Object> trans = new LinkedHashMap<>();
    trans.put("mid", files.get("trans-mid"));
    trans.put("high", files.get("trans-high"));
    trans.put("galaxy", files.get("trans-galaxy"));
    out.put("trans", trans);
    out.put("sky", sky);
    out.put("planes", pickNumbered(files, "plane"));
    out.put("aliens", pickNumbered(files, "alien"));
    out.put("sfx", pickNumbered(files, "sfx"));
    out.put("space", pickNumbered(files, "space"));
    out.put("satellites", pickNumbered(files, "satellite"));
    out.put("birdFrames", collectFrames(files, "bird"));
    out.put("satFrames", collectFrames(files, "satellite"));
    out.put("boomFrames", collectFrames(files, "boom"));
    out.put("bgTheme", files.get("bg-theme"));
    out.put("bgBet", files.get("bg-bet"));
    out.put("bgAuth", files.get("bg-auth"));
    out.put("soundOn", files.get("sound-on"));
    out.put("soundOff", files.get("sound-off"));
    for (Map.Entry<String, String> e : files.entrySet())
      if (e.getKey().startsWith("btn-")) out.put(e.getKey(), e.getValue());
    return out;
  }

  /** Кадры вида <kind><set>_f<номер> с любыми номерами (f01, f001, пропуски), сортировка по номеру кадра. */
  static Map<String, Object> collectFrames(Map<String, String> files, String kind) {
    Map<String, TreeMap<Integer, String>> sets = new TreeMap<>();
    java.util.regex.Pattern pat = java.util.regex.Pattern.compile("^" + kind + "(\\d+)_f(\\d+)$");
    for (Map.Entry<String, String> e : files.entrySet()) {
      java.util.regex.Matcher m = pat.matcher(e.getKey());
      if (m.matches())
        sets.computeIfAbsent(m.group(1), k -> new TreeMap<>()).put(Integer.parseInt(m.group(2)), e.getValue());
    }
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, TreeMap<Integer, String>> s : sets.entrySet())
      out.put(s.getKey(), new ArrayList<Object>(s.getValue().values()));
    return out;
  }

  static List<Object> pickNumbered(Map<String, String> files, String prefix) {
    List<Object> out = new ArrayList<>();
    for (int i = 1; i <= 9; i++) {
      String p = files.get(prefix + i);
      if (p == null) break;
      out.add(p);
    }
    return out;
  }

  /** Заполнить пустые слоты assets в CFG значениями с диска (config.json важнее). */
  @SuppressWarnings("unchecked")
  static void fillAssetsFromDisk() {
    Object a = CFG.get("assets");
    if (!(a instanceof Map)) { CFG.put("assets", discoverAssets()); return; }
    fillNulls((Map<String, Object>) a, discoverAssets());
  }

  @SuppressWarnings("unchecked")
  static void fillNulls(Map<String, Object> base, Map<String, Object> disc) {
    for (Map.Entry<String, Object> e : disc.entrySet()) {
      Object bv = base.get(e.getKey());
      if (bv == null) base.put(e.getKey(), e.getValue());
      else if (bv instanceof Map && e.getValue() instanceof Map) fillNulls((Map<String, Object>) bv, (Map<String, Object>) e.getValue());
      else if (bv instanceof List && ((List<Object>) bv).isEmpty() && e.getValue() instanceof List) base.put(e.getKey(), e.getValue());
    }
  }

  // ------------------------- HTTP -------------------------

  /** Раздача статики /assets/* из папки assets рядом с сервером. */
  static void serveAsset(HttpExchange ex, String p) throws IOException {
    try {
      Path base = Path.of("assets").toAbsolutePath().normalize();
      Path f = base.resolve(p.substring("/assets/".length())).normalize();
      if (!f.startsWith(base) || !Files.isRegularFile(f)) { json(ex, 404, Map.of("error", "file not found")); return; }
      String n = f.getFileName().toString().toLowerCase(Locale.US);
      String type = n.endsWith(".png") ? "image/png"
        : n.endsWith(".jpg") || n.endsWith(".jpeg") ? "image/jpeg"
        : n.endsWith(".svg") ? "image/svg+xml"
        : n.endsWith(".gif") ? "image/gif"
        : n.endsWith(".webp") ? "image/webp"
        : n.endsWith(".mp3") ? "audio/mpeg"
        : n.endsWith(".ogg") ? "audio/ogg"
        : n.endsWith(".wav") ? "audio/wav"
        : n.endsWith(".woff2") ? "font/woff2"
        : n.endsWith(".woff") ? "font/woff"
        : n.endsWith(".ttf") ? "font/ttf"
        : n.endsWith(".otf") ? "font/otf" : "application/octet-stream";
      byte[] b = Files.readAllBytes(f);
      ex.getResponseHeaders().set("Content-Type", type);
      ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
      ex.sendResponseHeaders(200, b.length);
      try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    } catch (Exception e) { json(ex, 500, Map.of("error", String.valueOf(e))); }
  }

  static String indexHtml() {
    try {
      Path p = Path.of("index.html");
      if (Files.exists(p)) return Files.readString(p, StandardCharsets.UTF_8);
    } catch (Exception ignored) { }
    return EMBEDDED_INDEX;
  }

  static void send(HttpExchange ex, int code, String type, String body) throws IOException {
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", type);
    ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
    ex.sendResponseHeaders(code, b.length);
    try (OutputStream o = ex.getResponseBody()) { o.write(b); }
  }

  static void json(HttpExchange ex, int code, Object o) throws IOException {
    send(ex, code, "application/json; charset=utf-8", Json.stringify(o));
  }

  static String param(HttpExchange ex, String name) {
    String q = ex.getRequestURI().getQuery();
    if (q == null) return null;
    for (String part : q.split("&")) {
      String[] kv = part.split("=", 2);
      if (kv.length == 2 && kv[0].equals(name)) return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
    }
    return null;
  }

  static class Router implements HttpHandler {
    @Override public void handle(HttpExchange ex) throws IOException {
      try {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        if (ex.getRequestMethod().equals("OPTIONS")) {
          ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
          ex.sendResponseHeaders(204, -1);
          return;
        }
        String p = ex.getRequestURI().getPath();
        if (p.equals("/") || p.equals("/index.html")) {
          send(ex, 200, "text/html; charset=utf-8", indexHtml());
          return;
        }
        if (p.startsWith("/assets/")) { serveAsset(ex, p); return; }
        if (p.startsWith("/api/")) { routeApi(ex, p); return; }
        json(ex, 404, Map.of("error", "not found"));
      } catch (ApiError e) {
        json(ex, e.code, Map.of("error", e.getMessage()));
      } catch (Exception e) {
        json(ex, 500, Map.of("error", String.valueOf(e)));
      } finally {
        ex.close();
      }
    }
  }

  @SuppressWarnings("unchecked")
  static void routeApi(HttpExchange ex, String p) throws IOException {
    String m = ex.getRequestMethod();
    Map<String, Object> body = new LinkedHashMap<>();
    if (m.equals("POST")) {
      String s = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      if (!s.isBlank()) {
        Object parsed = Json.parse(s);
        if (parsed instanceof Map) body = (Map<String, Object>) parsed;
      }
    }
    switch (p) {
      case "/api/version": json(ex, 200, Map.of("version", VERSION, "game", "Воздушный полет")); break;
      case "/api/auth/register": json(ex, 200, register(body)); break;
      case "/api/auth/login": json(ex, 200, login(body)); break;
      case "/api/admin/users": requireAdmin(ex); {
        List<Map<String, Object>> arr = new ArrayList<>();
        for (User u : USERS.values()) arr.add(u.toMap());
        json(ex, 200, arr);
        break;
      }
      case "/api/admin/rounds": requireAdmin(ex); json(ex, 200, new ArrayList<>(HISTORY)); break;
      case "/api/user": json(ex, 200, auth(ex).toMap()); break;
      case "/api/balance/add": {
        long a = body.get("amount") instanceof Number ? ((Number) body.get("amount")).longValue() : 100;
        User u = auth(ex);
        u.bonusBalance += a;
        saveUsers();
        json(ex, 200, u.toMap());
        break;
      }
      case "/api/config":
        if (m.equals("POST")) { requireAdmin(ex); deepMerge(CFG, body); saveCfg(); }
        else {
          try {
            Map<String, Object> fresh = (Map<String, Object>) Json.parse(DEFAULT_CFG);
            if (Files.exists(CFG_PATH)) deepMerge(fresh, (Map<String, Object>) Json.parse(Files.readString(CFG_PATH, StandardCharsets.UTF_8)));
            CFG = fresh;
            fillAssetsFromDisk();
          } catch (Exception ignored) { }
        }
        json(ex, 200, CFG);
        break;
      case "/api/assets-check": json(ex, 200, assetsReport()); break;
      case "/api/history": json(ex, 200, new ArrayList<>(HISTORY)); break;
      case "/api/round/start": {
        String theme = String.valueOf(body.get("theme"));
        long amount = body.get("amount") instanceof Number ? ((Number) body.get("amount")).longValue() : -1;
        int booster = body.get("booster") instanceof Number ? ((Number) body.get("booster")).intValue() : -1;
        json(ex, 200, startRound(auth(ex), theme, amount, booster));
        break;
      }
      case "/api/round/state": auth(ex); json(ex, 200, roundState(require(param(ex, "id")), System.nanoTime())); break;
      case "/api/round/cashout": json(ex, 200, cashout(auth(ex), String.valueOf(body.get("id")))); break;
      case "/api/leaderboard": {
        String period = param(ex, "period");
        String today = java.time.LocalDate.now().toString();
        // смена дня: архивируем вчерашний день в leaders.json (лидерборд сохраняется)
        if (!today.equals(leaderDay)) {
          Map<String, Object> snap = new LinkedHashMap<>();
          snap.put("date", leaderDay);
          snap.put("players", topPlayers(true, leaderDay));
          LEADER_SNAPS.add(snap);
          while (LEADER_SNAPS.size() > 30) LEADER_SNAPS.remove(0);
          leaderDay = today;
          saveLeaders();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if ("yesterday".equals(period)) {
          out.put("players", LEADER_SNAPS.isEmpty() ? new ArrayList<>() : LEADER_SNAPS.get(LEADER_SNAPS.size() - 1).get("players"));
          json(ex, 200, out);
          break;
        }
        boolean daily = "daily".equals(period);
        out.put("players", topPlayers(daily, daily ? today : ""));
        out.put("endsAt", java.time.LocalDate.now().plusDays(1).atStartOfDay()
          .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        json(ex, 200, out);
        break;
      }
      case "/api/gift/open": json(ex, 200, openGift(auth(ex))); break;
      case "/api/round/verify": {
        auth(ex);
        Round r = require(param(ex, "id"));
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("seed", r.seed);
        v.put("crashPoint", r.crashPoint);
        v.put("hash", r.hash);
        json(ex, 200, v);
        break;
      }
      default: json(ex, 404, Map.of("error", "unknown endpoint"));
    }
  }

  // ------------------------- запуск -------------------------

  @SuppressWarnings("unchecked")
  static void loadCfg() {
    CFG = (Map<String, Object>) Json.parse(DEFAULT_CFG);
    try {
      if (Files.exists(CFG_PATH)) deepMerge(CFG, (Map<String, Object>) Json.parse(Files.readString(CFG_PATH, StandardCharsets.UTF_8)));
    } catch (Exception e) {
      System.out.println("config.json не прочитан, используются значения по умолчанию: " + e);
    }
    fillAssetsFromDisk();
  }

  static void saveCfg() {
    try { Files.writeString(CFG_PATH, Json.stringify(CFG), StandardCharsets.UTF_8); }
    catch (Exception e) { System.out.println("Не удалось сохранить config.json: " + e); }
  }

  static void seedBots() {
    if (!HISTORY.isEmpty()) return;
    String[] names = {"Ан***", "Мар***", "Ив***", "Оль***", "Дм***", "Св***", "Ки***", "Не***", "Па***", "Ел***", "Та***", "Ро***"};
    Round tmp = new Round();
    for (int i = 0; i < 14; i++) {
      tmp.id = "seed" + i;
      String theme = i % 2 == 0 ? "RED" : "GREEN";
      List<Object> bl = (List<Object>) CFG.get("boosters");
      int booster = ((Number) bl.get(RNG.nextInt(bl.size()))).intValue();
      long amount = (long) gd(CFG, "min_bet", 1) * (1 + RNG.nextInt(20));
      double coef = genCrash(tmp);
      boolean winF = RNG.nextDouble() < 0.42;
      double coefOut = winF ? Math.max(1.01, coef * 0.55) : coef;
      long win = winF ? (long) Math.floor(amount * coefOut) : 0;
      Map<String, Object> h = new LinkedHashMap<>();
      h.put("user", names[RNG.nextInt(names.length)]);
      h.put("theme", theme);
      h.put("bet", amount);
      h.put("booster", booster);
      h.put("coef", Math.round(coefOut * 100.0) / 100.0);
      h.put("crash", Math.round(coef * 100.0) / 100.0);
      h.put("result", winF ? "WIN" : "LOSS");
      h.put("win", win);
      h.put("time", Instant.now().minusSeconds(RNG.nextInt(7200)).toString());
      HISTORY.add(h);
    }
  }

  public static void main(String[] args) throws Exception {
    loadCfg();
    loadUsers();
    loadLeaders();
    if (USERS.size() <= 1) {
      String[] botNames = {"Ал***", "Ми***", "Се***", "Вл***", "Ге***", "По***"};
      for (int i = 0; i < botNames.length; i++) {
        User b = newUser("bot" + (i + 1) + "@demo.local", hex(randomBytes(8)), false);
        b.name = botNames[i];
        b.bonusBalance = 200 + RNG.nextInt(4800);
        b.points = 100 + RNG.nextInt(4900);
        b.rounds = 3 + RNG.nextInt(60);
        b.dayStamp = java.time.LocalDate.now().toString();
        b.dayPoints = RNG.nextInt(600);
      }
      saveUsers();
      System.out.println("Лидерборд: добавлено " + botNames.length + " демо-игроков");
    }
    seedBots();
    int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
    HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext("/", new Router());
    server.setExecutor(Executors.newFixedThreadPool(8));
    server.start();
    System.out.println("Воздушный полет v" + VERSION + " запущен: http://localhost:" + port);
    System.out.println("Пользователей: " + USERS.size() + " | вход: email+пароль | админ: admin@mail.com / 12345678");
  }

  // ------------------------- мини-JSON -------------------------

  static class Json {
    @SuppressWarnings("unchecked")
    static String stringify(Object o) {
      StringBuilder b = new StringBuilder();
      if (o == null) b.append("null");
      else if (o instanceof String) { b.append('"'); for (char ch : ((String) o).toCharArray()) {
        if (ch == '"' || ch == '\\') b.append('\\').append(ch);
        else if (ch == '\n') b.append("\\n");
        else if (ch == '\r') b.append("\\r");
        else if (ch == '\t') b.append("\\t");
        else b.append(ch);
      } b.append('"'); }
      else if (o instanceof Number || o instanceof Boolean) b.append(o.toString());
      else if (o instanceof Map) {
        b.append('{'); boolean first = true;
        for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
          if (!first) b.append(','); first = false;
          b.append('"').append(e.getKey()).append("\":");
          stringify(e.getValue(), b);
        }
        b.append('}');
      } else if (o instanceof List) {
        b.append('['); boolean first = true;
        for (Object x : (List<Object>) o) { if (!first) b.append(','); first = false; stringify(x, b); }
        b.append(']');
      } else b.append('"').append(o).append('"');
      return b.toString();
    }

    static void stringify(Object o, StringBuilder b) { b.append(stringify(o)); }

    static Object parse(String s) { P p = new P(s); Object v = p.val(); p.ws(); return v; }

    static class P {
      final String s; int i;
      P(String s) { this.s = s; }
      void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
      char c() { return s.charAt(i); }
      void expect(char ch) { ws(); if (i >= s.length() || c() != ch) throw new RuntimeException("JSON: ожидался '" + ch + "' на позиции " + i); i++; }
      Object val() {
        ws();
        if (i >= s.length()) throw new RuntimeException("JSON: неожиданный конец");
        char ch = c();
        if (ch == '{') {
          Map<String, Object> m = new LinkedHashMap<>();
          i++; ws();
          if (c() == '}') { i++; return m; }
          while (true) {
            String k = str(); expect(':'); m.put(k, val()); ws();
            if (c() == ',') { i++; continue; }
            expect('}'); return m;
          }
        }
        if (ch == '[') {
          List<Object> l = new ArrayList<>();
          i++; ws();
          if (c() == ']') { i++; return l; }
          while (true) {
            l.add(val()); ws();
            if (c() == ',') { i++; continue; }
            expect(']'); return l;
          }
        }
        if (ch == '"') return str();
        if (ch == 't') { i += 4; return Boolean.TRUE; }
        if (ch == 'f') { i += 5; return Boolean.FALSE; }
        if (ch == 'n') { i += 4; return null; }
        int st = i;
        while (i < s.length() && "-+.eE0123456789".indexOf(c()) >= 0) i++;
        String num = s.substring(st, i);
        if (num.contains(".") || num.contains("e") || num.contains("E")) return Double.parseDouble(num);
        return Long.parseLong(num);
      }
      String str() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
          if (i >= s.length()) throw new RuntimeException("JSON: незакрытая строка");
          char ch = c(); i++;
          if (ch == '"') break;
          if (ch == '\\') {
            char e2 = c(); i++;
            if (e2 == 'n') b.append('\n');
            else if (e2 == 't') b.append('\t');
            else if (e2 == 'r') b.append('\r');
            else if (e2 == 'u') { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
            else b.append(e2);
          } else b.append(ch);
        }
        return b.toString();
      }
    }
  }
}
