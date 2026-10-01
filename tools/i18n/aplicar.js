// Idiomas do app: lê app/src/main/assets/index.html, embrulha em L("...") cada texto em português que tem
// tradução no dict.json e escreve assets/i18n.js (tabela EN + L()).
// Uso: cd tools/i18n && npm i acorn acorn-walk && node aplicar.js
// Para um texto novo: escreva em português no código, adicione "frase": "phrase" ao dict.json e rode de novo.
const acorn = require("acorn"), walk = require("acorn-walk"), fs = require("fs"), path = require("path");
const assets = path.join(__dirname, "../../app/src/main/assets");
const htmlPath = path.join(assets, "index.html");
let html = fs.readFileSync(htmlPath, "utf8");
const a = html.lastIndexOf("<script>") + 8, b = html.lastIndexOf("</script>");
let src = html.slice(a, b);
const dict = JSON.parse(fs.readFileSync(path.join(__dirname, "dict.json"), "utf8"));
const keys = Object.keys(dict).sort((x, y) => y.length - x.length);
const esc = s => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
const re = new RegExp("(?<![\\p{L}\\p{N}_])(?:" + keys.map(esc).join("|") + ")(?![\\p{L}\\p{N}_])", "gu");
const tr = s => s.replace(re, m => dict[m]);
const skipApi = new Set(["includes", "indexOf", "getItem", "setItem", "querySelector", "querySelectorAll", "getElementById", "add", "remove", "toggle", "contains", "startsWith", "endsWith", "test", "match", "replace", "split", "getAttribute", "setAttribute"]);
const EN = {}, edits = [], faltam = new Set();
const ast = acorn.parse(src, { ecmaVersion: 2022 });
walk.ancestor(ast, {
  Literal(node, st, anc) {
    if (typeof node.value !== "string") return;
    const p = anc[anc.length - 2];
    if (anc.some(n => n.type === "VariableDeclarator" && n.id.name && ["LEGADO_CAT", "OUTRAS_REC"].includes(n.id.name))) return;
    const jaEmbrulhado = p.type === "CallExpression" && p.callee.type === "Identifier" && p.callee.name === "L" && p.arguments[0] === node;
    if (!jaEmbrulhado) {
      if (p.type === "BinaryExpression" && /[=!]==?/.test(p.operator)) return;
      if (p.type === "SwitchCase") return;
      if (p.type === "Property" && p.key === node) return;
      if (p.type === "MemberExpression" && p.property === node) return;
      if (p.type === "CallExpression" && p.callee.type === "MemberExpression" && skipApi.has(p.callee.property.name)) return;
      if (p.type === "CallExpression" && p.callee.type === "Identifier" && ["$", "$$", "gravarPref", "lerPref", "L", "LE", "dtxt", "K"].includes(p.callee.name) && p.arguments[0] === node) return;
    }
    const en = tr(node.value);
    if (en !== node.value) { EN[node.value] = en; if (!jaEmbrulhado) edits.push([node.start, node.end]); }
    else if (jaEmbrulhado) faltam.add(node.value);
  }
});
edits.sort((x, y) => y[0] - x[0]).forEach(([s, e]) => { src = src.slice(0, s) + "L(" + src.slice(s, e) + ")" + src.slice(e); });
fs.writeFileSync(htmlPath, html.slice(0, a) + src + html.slice(b));
const runtime = `/* Gerado por tools/i18n/aplicar.js: não edite à mão. */
const LANG = (() => { try { const v = localStorage.getItem("fin.lang"); if (v === "en" || v === "pt") return v; } catch (e) {} return String(navigator.language || "pt").toLowerCase().startsWith("en") ? "en" : "pt"; })();
const EN = ${JSON.stringify(Object.assign({}, dict, EN))};
const L = s => LANG === "en" ? (EN[s] || s) : s;
try { document.documentElement.lang = LANG === "en" ? "en" : "pt-BR"; } catch (e) {}

const CAT_EN = {"Alimentação": "Food", "Bebidas": "Drinks", "Guloseimas": "Sweets", "Padaria": "Bakery", "Restaurante": "Restaurant", "Supermercado": "Groceries", "Suplementos": "Supplements", "Casa": "Home", "Água": "Water", "Aluguel": "Rent", "Condomínio": "HOA fees", "Eletrodomésticos": "Appliances", "Empregada": "Housekeeper", "Gás": "Gas", "Imposto Predial": "Property tax", "Internet": "Internet", "Luz": "Electricity", "Manutenção da Casa": "Home maintenance", "Materiais de Limpeza": "Cleaning supplies", "Móveis": "Furniture", "TV por Assinatura": "TV subscription", "Utensílios": "Kitchenware", "Dívidas": "Debts", "Doação": "Donations", "Dízimo": "Tithe", "Educação": "Education", "Gastos Pessoais": "Personal", "Academia": "Gym", "Celular": "Mobile phone", "Cosméticos": "Cosmetics", "Eletrônicos": "Electronics", "Internet Móvel": "Mobile data", "Roupas e Calçados": "Clothes & shoes", "Salão de Beleza": "Hair & beauty", "Gastos Profissionais": "Work expenses", "Equipamentos": "Equipment", "Insumos": "Supplies", "Outros": "Other", "Papelaria": "Stationery", "Imposto": "Tax", "Lazer - Passeios": "Leisure - Outings", "Pet": "Pet", "Saúde": "Health", "Farmácia": "Pharmacy", "Plano de Saúde": "Health insurance", "Transporte": "Transport", "Combustível": "Fuel", "Estacionamento": "Parking", "Lava Jato": "Car wash", "Manutenção": "Maintenance", "Metrô/Ônibus": "Metro/Bus", "Multas": "Fines", "Pedágio": "Tolls", "Seguro": "Insurance", "Táxi": "Taxi", "Receitas": "Income", "Comissão": "Commission", "Outras Receitas": "Other income", "Salário": "Salary", "Trabalho Particular": "Freelance work", "Geral": "General"};
/* Tradução do que não passa por L(): nomes de categorias, menu fixo, atributos. Roda a cada mudança da tela. */
const LE = (pt, en) => LANG === "en" ? en : pt;
if (LANG === "en") {
  const tr1 = t => { const m = t.match(/^(\\s*(?:↳\\s*)?(?:[^\\p{L}\\p{N}]*?\\s)?)(.*?)(\\s*)$/su); if (!m) return t; const c = m[2];
    const v = CAT_EN[c] || EN[c]; return v ? m[1] + v + m[3] : t; };
  const seg = t => { if (!/[A-Za-zÀ-ú]/.test(t)) return t; const partes = t.split(/( · | › )/); let mudou = false;
    const o = partes.map(p => { const n = tr1(p); if (n !== p) mudou = true; return n; }); return mudou ? o.join("") : t; };
  let busy = false;
  const passa = raiz => { if (busy) return; busy = true;
    try {
      const w = document.createTreeWalker(raiz, NodeFilter.SHOW_TEXT);
      for (let n = w.nextNode(); n; n = w.nextNode()) { const p = n.parentNode; if (p && /^(SCRIPT|STYLE|TEXTAREA|INPUT)$/.test(p.nodeName)) continue; const v = seg(n.nodeValue); if (v !== n.nodeValue) n.nodeValue = v; }
      if (raiz.querySelectorAll) raiz.querySelectorAll("[aria-label],[placeholder],[title]").forEach(e => ["aria-label", "placeholder", "title"].forEach(a => { const v = e.getAttribute(a); if (v) { const n = EN[v] || CAT_EN[v]; if (n) e.setAttribute(a, n); } }));
    } catch (e) {} busy = false; };
  const iniciar = () => { passa(document.body); let ag = 0; new MutationObserver(() => { if (ag) return; ag = setTimeout(() => { ag = 0; passa(document.body); }, 0); }).observe(document.body, {childList: true, subtree: true, characterData: true}); };
  if (document.body) iniciar(); else document.addEventListener("DOMContentLoaded", iniciar);
}
`;
fs.writeFileSync(path.join(assets, "i18n.js"), runtime);
console.log("novos embrulhos:", edits.length, "· frases na tabela:", Object.keys(EN).length, faltam.size ? "· sem tradução: " + [...faltam].join(" | ") : "");
