/*
 * Motor do Finanças em JavaScript: a mesma API da Ponte Android (window.App), para rodar no iPhone
 * (dentro do app iOS) ou num navegador. No Android o app nativo já define window.App e este arquivo não faz nada.
 *
 * Dados: registros { id, tipo, dono, sh, dados, apagado, sujo, ver, at } — idêntico ao SQLite do Android,
 * sincronizados com o mesmo Supabase. Guardados no aparelho (arquivo nativo no iOS, localStorage no navegador).
 */
(function () {
  "use strict";
  if (window.App) return;

  const IOS = !!(window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.financas);
  const nativo = (acao, dados) => { try { if (IOS) window.webkit.messageHandlers.financas.postMessage(Object.assign({ acao }, dados || {})); } catch (e) {} };
  const INFO = window.__FIN_APP || { versao: "1.9 (web)", codigo: 0, plataforma: IOS ? "ios" : "web" };

  // ================= Persistência =================
  const INICIAL = window.__FIN_INICIAL || {};
  function lerBloco(chave, padrao) {
    try {
      if (INICIAL[chave] != null) return typeof INICIAL[chave] === "string" ? JSON.parse(INICIAL[chave]) : INICIAL[chave];
    } catch (e) {}
    try { const t = localStorage.getItem("fin." + chave); if (t) return JSON.parse(t); } catch (e) {}
    return padrao;
  }
  const blocos = {
    registros: lerBloco("registros", {}),
    ajustes: lerBloco("ajustes", {}),
    nuvem: lerBloco("nuvem", {})
  };
  const pendenteGravar = new Set();
  let timerGravar = null;
  function gravar(chave) {
    pendenteGravar.add(chave);
    if (!timerGravar) timerGravar = setTimeout(descarregar, 250);
  }
  function descarregar() {
    clearTimeout(timerGravar); timerGravar = null;
    pendenteGravar.forEach(chave => {
      const txt = JSON.stringify(blocos[chave]);
      if (IOS) nativo("gravar", { chave, valor: txt });
      try { localStorage.setItem("fin." + chave, txt); } catch (e) {}
    });
    pendenteGravar.clear();
  }
  document.addEventListener("visibilitychange", () => { if (document.visibilityState === "hidden") descarregar(); });
  window.addEventListener("pagehide", descarregar);

  // preferências simples
  const pref = {
    get: (b, k, padrao) => (blocos[b][k] === undefined || blocos[b][k] === null) ? padrao : blocos[b][k],
    set: (b, k, v) => { blocos[b][k] = v; gravar(b); },
    del: (b, ...ks) => { ks.forEach(k => delete blocos[b][k]); gravar(b); }
  };

  function novoId() {
    const b = new Uint8Array(16); crypto.getRandomValues(b);
    b[6] = (b[6] & 0x0f) | 0x40; b[8] = (b[8] & 0x3f) | 0x80;
    const h = [...b].map(x => x.toString(16).padStart(2, "0")).join("");
    return h.slice(0, 8) + "-" + h.slice(8, 12) + "-" + h.slice(12, 16) + "-" + h.slice(16, 20) + "-" + h.slice(20);
  }

  // ================= Banco (registros) =================
  const Banco = {
    get R() { return blocos.registros; },
    todos(tipo) { return Object.values(this.R).filter(r => r.tipo === tipo && !r.apagado); },
    todosComApagados(tipo) { return Object.values(this.R).filter(r => r.tipo === tipo); },
    um(id) { return this.R[id] || null; },
    existeTipoDoDono(tipo, dono) { return Object.values(this.R).some(r => r.tipo === tipo && r.dono === dono); },
    salvar(r) {
      const ant = this.R[r.id];
      this.R[r.id] = { id: r.id, tipo: r.tipo, dono: r.dono, sh: !!r.sh, dados: r.dados || {}, apagado: !!r.apagado,
        sujo: true, ver: (ant ? ant.ver : 0) + 1, at: ant ? ant.at : null };
      gravar("registros");
    },
    sujos(limite) { return Object.values(this.R).filter(r => r.sujo).slice(0, limite || 200).map(r => ({ reg: r, ver: r.ver })); },
    contarSujos() { return Object.values(this.R).filter(r => r.sujo).length; },
    limpar(id, ver) { const r = this.R[id]; if (r && r.ver === ver) { r.sujo = false; gravar("registros"); } },
    aplicarRemoto(r, at) {
      const ant = this.R[r.id];
      if (ant && ant.sujo) return;   // mudança local ainda não enviada tem prioridade
      this.R[r.id] = { id: r.id, tipo: r.tipo, dono: r.dono, sh: !!r.sh, dados: r.dados || {}, apagado: !!r.apagado, sujo: false, ver: 0, at };
      gravar("registros");
    },
    trocarDono(de, para) {
      Object.values(this.R).forEach(r => { if (r.dono === de) { r.dono = para; r.sujo = true; r.ver++; } });
      gravar("registros");
    },
    apagarTudoDoDono(dono) { Object.keys(this.R).forEach(id => { if (this.R[id].dono === dono) delete this.R[id]; }); gravar("registros"); },
    apagarTudo() { blocos.registros = {}; gravar("registros"); },
    remover(id) { delete this.R[id]; gravar("registros"); },
    exportar() { return Object.values(this.R).map(r => ({ id: r.id, tipo: r.tipo, dono: r.dono, sh: r.sh, dados: r.dados, apagado: r.apagado })); }
  };
  const copia = o => JSON.parse(JSON.stringify(o));

  // ================= Sessão (conta e servidor) =================
  const URL_PADRAO = "https://yalkbujtjlrryttmccrw.supabase.co";
  const CHAVE_PADRAO = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InlhbGtidWp0amxycnl0dG1jY3J3Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTA0NDA5NDgsImV4cCI6MjEwNjAxNjk0OH0.zVc8dd2SflgXQRFUPskwvcdHLHYys_sZIitGSqe8IYo";
  const S_ = k => pref.get("nuvem", k, "");
  const Sessao = {
    url: () => S_("url") || URL_PADRAO,
    chave: () => S_("chave") || CHAVE_PADRAO,
    configurada() { return !!(this.url() && this.chave()); },
    uid: () => S_("uid") || null,
    logado() { return !!this.uid(); },
    eu() { return this.uid() || "local"; },
    email: () => S_("email"), casal: () => S_("casal"), codigo: () => S_("codigo"),
    parceiroId: () => S_("parceiroId"), parceiroNome: () => S_("parceiroNome"),
    ultima: () => S_("ultima"), ultimaSyncMs: () => pref.get("nuvem", "ultimaMs", 0),
    erro: () => S_("erro"), convitePendente: () => S_("convitePendente"),
    access: () => S_("access"), refresh: () => S_("refresh"), expira: () => pref.get("nuvem", "expira", 0),
    proServidor: () => !!pref.get("nuvem", "proServidor", false), proExpira: () => pref.get("nuvem", "proExpira", 0),
    proAdmin: () => !!pref.get("nuvem", "proAdmin", false), proPendente: () => !!pref.get("nuvem", "proPendente", false),
    editar(obj) { Object.entries(obj).forEach(([k, v]) => { if (v === null) delete blocos.nuvem[k]; else blocos.nuvem[k] = v; }); gravar("nuvem"); },
    sair() {
      ["uid", "email", "access", "refresh", "expira", "casal", "codigo", "parceiroId", "parceiroNome", "ultima", "ultimaMs", "erro", "convitePendente",
        "proServidor", "proExpira", "proAdmin", "proPendente"]
        .forEach(k => delete blocos.nuvem[k]);
      gravar("nuvem");
    }
  };

  // ================= Ajustes deste aparelho =================
  const Ajustes = {
    nome: () => pref.get("ajustes", "nome", "Eu") || "Eu",
    salvarNome: n => pref.set("ajustes", "nome", (n || "").trim() || "Eu"),
    moeda: () => pref.get("ajustes", "moeda", "EUR"),
    salvarMoeda: c => pref.set("ajustes", "moeda", c === "BRL" ? "BRL" : "EUR"),
    conquistas: () => pref.get("ajustes", "conquistas", []),
    marcarConquista(id) { const a = this.conquistas().slice(); if (!a.includes(id)) { a.push(id); pref.set("ajustes", "conquistas", a); } }
  };

  // ================= Datas =================
  const agora = () => Date.now();
  const pad = (n, t) => String(n).padStart(t || 2, "0");
  const Datas = {
    hoje() { const d = new Date(); return { a: d.getFullYear(), m: d.getMonth() + 1, d: d.getDate() }; },
    mesAtual() { const h = this.hoje(); return h.a + "-" + pad(h.m); },
    diaHoje() { return new Date().getDate(); },
    diasNoMes(a, m) { if (a === undefined) { const h = this.hoje(); a = h.a; m = h.m; } return new Date(Date.UTC(a, m, 0)).getUTCDate(); },
    diaEfetivo(dia) { return Math.min(dia, this.diasNoMes()); },
    inicioDoMes() { const d = new Date(); return new Date(d.getFullYear(), d.getMonth(), 1).getTime(); },
    // datas "yyyy-mm-dd" tratadas em UTC para não sofrer com horário de verão
    parse(s) { const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s || ""); if (!m) return null; const d = new Date(Date.UTC(+m[1], +m[2] - 1, +m[3])); return d.getUTCMonth() === +m[2] - 1 ? d : null; },
    iso(d) { return d.getUTCFullYear() + "-" + pad(d.getUTCMonth() + 1) + "-" + pad(d.getUTCDate()); },
    somarDias(d, n) { return new Date(d.getTime() + n * 864e5); },
    hojeUTC() { const h = this.hoje(); return new Date(Date.UTC(h.a, h.m - 1, h.d)); }
  };

  // ================= Contas que se repetem =================
  function Conta(r) {
    const o = r.dados;
    return {
      id: r.id, nome: o.n || "", valor: +o.v || 0, dia: o.dia || 1, entrada: !!o.e, sh: r.sh, dono: r.dono,
      ini: o.ini || "", vezes: o.vezes || 0, variavel: !!o.var, categoria: o.c || "", freq: o.freq || "m", d0: o.d0 || "", pm: Math.max(1, o.pm || 1)
    };
  }
  const idxMes = m => { const p = (m || "").split("-"); if (p.length !== 2) return null; const a = parseInt(p[0]), b = parseInt(p[1]); return isNaN(a) || isNaN(b) ? null : a * 12 + b - 1; };
  function parcelaEm(c, mes) {
    const i0 = idxMes(c.ini);
    if (i0 === null) return c.vezes === 0 ? 1 : null;
    const im = idxMes(mes); if (im === null) return null;
    const i = im - i0;
    if (i < 0 || i % c.pm) return null;
    const n = i / c.pm;
    return c.vezes === 0 || n < c.vezes ? n + 1 : null;
  }
  function inicioConta(c) {
    const d = Datas.parse(c.d0); if (d) return d;
    const i = idxMes(c.ini); if (i === null) return null;
    const a = Math.floor(i / 12), m = i % 12 + 1;
    return new Date(Date.UTC(a, m - 1, Math.min(c.dia, Datas.diasNoMes(a, m))));
  }
  function ocorrenciasNoMes(c, a, m) {
    const primeiro = new Date(Date.UTC(a, m - 1, 1)), n = Datas.diasNoMes(a, m);
    const ultimo = new Date(Date.UTC(a, m - 1, n));
    if (c.freq !== "q" && c.freq !== "s") {
      const chave = a + "-" + pad(m);
      const p = parcelaEm(c, chave); if (p === null) return [];
      return [{ data: new Date(Date.UTC(a, m - 1, Math.min(c.dia, n))), n: p, chave }];
    }
    const ini = inicioConta(c); if (!ini) return [];
    const passo = c.freq === "q" ? 14 : 7;
    const dif = Math.round((primeiro - ini) / 864e5);
    let k = Math.max(0, Math.floor((dif + passo - 1) / passo));
    const lista = [];
    for (;;) {
      if (c.vezes > 0 && k >= c.vezes) break;
      const d = Datas.somarDias(ini, k * passo);
      if (d > ultimo) break;
      lista.push({ data: d, n: k + 1, chave: Datas.iso(d) });
      k++;
    }
    return lista;
  }
  const ocorrenciasAgora = c => { const h = Datas.hoje(); return ocorrenciasNoMes(c, h.a, h.m); };
  const ativaEm = (c, mes) => { const p = mes.split("-"); return ocorrenciasNoMes(c, +p[0], +p[1]).length > 0; };

  // ================= Categorias e interpretador =================
  const FIXAS = "Contas fixas", OUTROS = "Outros", SALARIO = "Salário", EXTRA = "Extra";
  const regras = [
    ["Compras", ["mercado livre", "shopee", "amazon", "shein", "aliexpress", "roupa", "loja", "tenis", "presente", "worten", "fnac"]],
    ["Mercado", ["supermercado", "mercado", "feira", "hortifruti", "atacad", "acougue", "talho", "continente", "pingo doce", "lidl", "aldi", "minipreco", "intermarche", "mercearia"]],
    ["Alimentação", ["almoco", "jantar", "lanche", "cafe", "restaurante", "ifood", "uber eats", "glovo", "bolt food", "pizza", "hamburg", "padaria", "pastelaria", "bar", "gelado", "sorvete", "pequeno-almoco"]],
    ["Hábitos", ["cigarro", "tabaco", "maco", "vape", "raspadinha", "aposta", "euromilhoes", "cerveja", "bebida"]],
    ["Transporte", ["uber", "bolt", "gasolina", "gasoleo", "combustivel", "posto", "autocarro", "onibus", "metro", "comboio", "cp ", "estacionamento", "portagem", "pedagio", "oficina", "via verde"]],
    ["Moradia", ["aluguel", "renda", "condominio", "luz", "energia", "edp", "agua", "gas", "internet", "meo", "nos ", "vodafone", "reforma"]],
    ["Saúde", ["farmacia", "remedio", "medicamento", "medico", "consulta", "exame", "ginasio", "academia", "dentista"]],
    ["Lazer", ["cinema", "concerto", "show", "viagem", "netflix", "spotify", "hbo", "disney", "jogo", "passeio", "festa"]],
    ["Educação", ["curso", "livro", "escola", "faculdade", "universidade", "propina", "apostila", "formacao"]]
  ];
  const regrasReceita = [
    [SALARIO, ["salario", "ordenado", "vencimento"]],
    ["Reembolso", ["reembolso", "devolveu", "devolucao", "estorno"]],
    ["Rendimentos", ["juros", "rendimento", "dividendo", "cashback"]]
  ];
  const apelidos = [
    ["Compras", ["compras", "compra"]], ["Mercado", ["mercado"]], ["Alimentação", ["alimentacao", "comida"]],
    ["Hábitos", ["habitos", "habito"]], ["Transporte", ["transporte", "transportes"]], ["Moradia", ["moradia", "casa"]],
    ["Saúde", ["saude"]], ["Lazer", ["lazer"]], ["Educação", ["educacao", "estudo", "estudos"]],
    [FIXAS, ["contas fixas", "conta fixa"]], [OUTROS, ["outros", "outro"]],
    [SALARIO, ["salario"]], ["Reembolso", ["reembolso"]], ["Rendimentos", ["rendimentos", "rendimento"]], [EXTRA, ["extra"]]
  ];
  const Categorias = {
    todas: regras.map(r => r[0]).concat([FIXAS, OUTROS]),
    receitas: regrasReceita.map(r => r[0]).concat([EXTRA]),
    normalizar: s => (" " + s.toLowerCase() + " ").normalize("NFD").replace(/\p{Mn}+/gu, ""),
    escrita(desc, receita) {
      const validas = receita ? this.receitas : this.todas;
      const palavras = desc.trim().split(/\s+/).filter(Boolean);
      const achar = trecho => {
        const n = this.normalizar(trecho.replace(/^#/, "")).trim();
        const a = apelidos.find(([c, lista]) => validas.includes(c) && lista.includes(n));
        return a ? a[0] : null;
      };
      for (let i = 0; i < palavras.length; i++) {
        const w = palavras[i];
        if (w.startsWith("#") && w.length > 1) { const c = achar(w); if (c) return [c, palavras.filter((_, k) => k !== i).join(" ")]; }
      }
      for (let n = 2; n >= 1; n--) {
        if (palavras.length <= n) continue;
        const c = achar(palavras.slice(-n).join(" "));
        if (c) return [c, palavras.slice(0, -n).join(" ")];
      }
      return null;
    },
    detectar(desc, receita) {
      const d = this.normalizar(desc || "");
      for (const [c, ps] of (receita ? regrasReceita : regras)) if (ps.some(p => d.includes(p))) return c;
      return receita ? EXTRA : OUTROS;
    }
  };

  // Frases ditadas (Siri, ditado do teclado): igual ao objeto Fala do Android
  const Fala = {
    normalizar(t) {
      let s = String(t || "").trim();
      s = s.replace(/(\d+)\s*(?:€|euros?|reais|r\$)\s+e\s+(\d{1,2})(?!\d)(?:\s*(?:cêntimos?|centimos?|centavos?))?/gi, (m, a, b) => a + "," + b.padStart(2, "0"));
      s = s.replace(/(\d+)\s+v[íi]rgula\s+(\d{1,2})(?!\d)/gi, "$1,$2");
      const rec = /^(?:eu\s+)?(?:recebi|ganhei)\s+/i;
      if (rec.test(s)) s = "+" + s.replace(rec, "");
      return s.replace(/^(?:eu\s+)?(?:gastei|paguei|comprei)\s+/i, "");
    },
    limparDescricao: d => d.trim().replace(/^(?:de|do|da|dos|das|no|na|nos|nas|em|com|pra|para)\s+/i, "").replace(/\s+(?:de|do|da|por|no|na|em|com)$/i, "").trim()
  };

  const Interpretador = {
    numero: /\d{1,3}(?:\.\d{3})+(?:,\d{1,2})?(?!\d)|\d+(?:[.,]\d{1,2})?(?!\d)/,
    milhar: /^\d{1,3}(?:\.\d{3})+$/,
    simbolos: () => /r\$|€|\beuros?\b|\beur\b|\breais\b/gi,
    paraNumero(s) {
      let x = String(s == null ? "" : s).trim().replace(this.simbolos(), "").trim();
      if (x.includes(",")) x = x.replace(/\./g, "").replace(",", ".");
      else if (this.milhar.test(x)) x = x.replace(/\./g, "");
      if (!/^[+-]?(\d+\.?\d*|\.\d+)$/.test(x)) return null;
      const v = parseFloat(x);
      return isNaN(v) ? null : v;
    },
    interpretar(texto, forcarReceita) {
      let t = Fala.normalizar(texto), receita = !!forcarReceita;
      if (t.startsWith("+")) { receita = true; t = t.slice(1).trim(); }
      const todos = [...t.matchAll(new RegExp(this.numero.source, "g"))];
      if (!todos.length) return null;
      // "2 cafés 3 euros": vale o número colado à moeda
      const m = todos.find(x => /^\s*(?:€|(?:euros?|eur|reais)\b)/i.test(t.slice(x.index + x[0].length)))
        || todos.find(x => /R\$$/i.test(t.slice(0, x.index).trimEnd())) || todos[0];
      const valor = this.paraNumero(m[0]); if (valor === null || valor <= 0) return null;
      let desc = Fala.limparDescricao((t.slice(0, m.index) + t.slice(m.index + m[0].length)).replace(this.simbolos(), "").replace(/\s+/g, " "));
      if (!desc) desc = receita ? "Entrada" : "Sem descrição";
      const e = Categorias.escrita(desc, receita);
      if (e) return { quando: agora(), valor, descricao: e[1], categoria: e[0], receita, escrita: true };
      return { quando: agora(), valor, descricao: desc, categoria: Categorias.detectar(desc, receita), receita, escrita: false };
    }
  };

  const Formato = {
    moeda: v => new Intl.NumberFormat(Ajustes.moeda() === "BRL" ? "pt-BR" : "pt-PT", { style: "currency", currency: Ajustes.moeda() }).format(v),
    prazo: d => d < 0 ? "atrasada " + (-d) + " dia(s)" : d === 0 ? "vence hoje" : d === 1 ? "vence amanhã" : "vence em " + d + " dias"
  };

  // ================= Armazém: operações do app =================
  const Armazem = {
    eu: () => Sessao.eu(),
    novo(tipo, sh, dados) { const id = novoId(); Banco.salvar({ id, tipo, dono: this.eu(), sh, dados }); Nuvem.agendar(); return id; },
    alterar(id, f) {
      const r = Banco.um(id); if (!r || r.dono !== this.eu()) return;
      const n = f(copia(r)); if (n) { Banco.salvar(n); Nuvem.agendar(); }
    },
    apagar(id) { this.alterar(id, r => (r.apagado = true, r)); },
    restaurar(id) { this.alterar(id, r => (r.apagado = false, r)); },

    paraLanc(r) {
      const o = r.dados, rec = o.t === "r", d = o.d || "";
      return { id: r.id, q: +o.q || 0, v: +o.v || 0, d, c: o.c || Categorias.detectar(d, rec), receita: rec, sh: r.sh, dono: r.dono, cf: o.cf || "" };
    },
    lancamentos() { return Banco.todos("lanc").map(r => this.paraLanc(r)).sort((a, b) => b.q - a.q); },
    dadosLanc: g => ({ q: g.quando, v: g.valor, d: g.descricao, c: g.categoria, t: g.receita ? "r" : "d" }),
    adicionar(g, sh) { return this.novo("lanc", !!sh, this.dadosLanc(g)); },
    editar(id, g, sh) {
      const r = Banco.um(id); if (!r || r.dono !== this.eu()) return;
      const dados = this.dadosLanc(g);
      if (r.dados.cf) dados.cf = r.dados.cf;
      if (r.sh && !sh) {
        Banco.salvar(Object.assign(copia(r), { apagado: true }));
        Banco.salvar({ id: novoId(), tipo: "lanc", dono: r.dono, sh: false, dados });
      } else Banco.salvar(Object.assign(copia(r), { sh, dados }));
      Nuvem.agendar();
    },
    duplicar(id) {
      const r = Banco.um(id); if (!r) return;
      const l = this.paraLanc(r);
      this.adicionar({ quando: agora(), valor: l.v, descricao: l.d, categoria: l.c, receita: l.receita }, l.sh && l.dono === this.eu());
    },
    mudarCategoria(id, c) { this.alterar(id, r => (r.dados.c = c, r)); },

    curtidas: () => Banco.todos("curtida"),
    comentarios: () => Banco.todos("coment"),
    compartilhadoDe: alvo => { const r = Banco.um(alvo); return r ? r.sh : false; },
    curtir(alvo) {
      const me = this.eu();
      const minha = Banco.todos("curtida").find(r => r.dono === me && r.dados.alvo === alvo);
      if (minha) this.apagar(minha.id); else this.novo("curtida", this.compartilhadoDe(alvo), { alvo });
    },
    comentar(alvo, t) { this.novo("coment", this.compartilhadoDe(alvo), { alvo, t, a: Ajustes.nome(), q: agora() }); },

    contas() { return Banco.todos("conta").map(Conta).sort((a, b) => a.dia - b.dia); },
    contasDoMes() { const m = Datas.mesAtual(); return this.contas().filter(c => ativaEm(c, m)); },
    adicionarConta(nome, valor, dia, entrada, sh, ini, vezes, variavel, categoria, freq, d0) {
      return this.novo("conta", sh, { n: nome, v: valor, dia, e: entrada, ini, vezes, var: variavel, c: categoria, freq, d0 });
    },
    editarConta(id, nome, valor, categoria, dia) {
      this.alterar(id, r => {
        Object.assign(r.dados, { n: nome, v: valor, c: categoria });
        if (dia >= 1 && dia <= 31 && (r.dados.freq || "m") === "m") r.dados.dia = dia;
        return r;
      });
    },
    pagoDe: (contaId, chave) => Banco.todos("pago").find(r => r.dados.conta === contaId && r.dados.mes === chave) || null,
    ocorrenciasComPagamento(c) { return ocorrenciasAgora(c).map(o => [o, this.pagoDe(c.id, o.chave)]); },
    alternarPaga(contaId, valorReal, chave) {
      const c = this.contas().find(x => x.id === contaId); if (!c) return false;
      const ocs = this.ocorrenciasComPagamento(c); if (!ocs.length) return false;
      let alvo;
      if (chave) { alvo = ocs.find(x => x[0].chave === chave); if (!alvo) return false; }
      else alvo = ocs.find(x => !x[1]) || ocs[ocs.length - 1];
      const [oc, pago] = alvo, me = this.eu();
      if (pago) {
        if (pago.dono !== me) return false;
        this.apagar(pago.id);
        const ini = Datas.inicioDoMes();
        const l = Banco.todos("lanc").find(r => r.dono === me && r.dados.cf === contaId &&
          (r.dados.oc === oc.chave || (!r.dados.oc && c.freq === "m" && (+r.dados.q || 0) >= ini)));
        if (l) this.apagar(l.id);
        return true;
      }
      this.novo("pago", c.sh, { conta: contaId, mes: oc.chave });
      const nome = c.vezes > 1 ? c.nome + " (" + oc.n + "/" + c.vezes + ")" : c.nome;
      const d = { q: agora(), v: valorReal != null ? valorReal : c.valor, d: nome, cf: contaId, oc: oc.chave };
      if (c.entrada) { d.t = "r"; d.c = c.categoria || Categorias.detectar(c.nome, true); }
      else { d.t = "d"; d.c = c.categoria || FIXAS; }
      this.novo("lanc", c.sh, d);
      return true;
    },

    // No servidor com o Pro vale a validade dada pelo admin; sem ele, o registro "plano" ativado no app
    premium() {
      if (Sessao.proServidor()) return Sessao.proAdmin() || Sessao.proExpira() > agora();
      const me = this.eu(); return Banco.todos("plano").some(r => r.dono === me && r.dados.premium);
    },
    definirPremium(ativo) {
      const me = this.eu(), meus = Banco.todos("plano").filter(r => r.dono === me);
      const d = { premium: !!ativo, q: agora() };
      if (!meus.length) this.novo("plano", false, d);
      else { meus.forEach(r => Banco.salvar(Object.assign(copia(r), { dados: d }))); Nuvem.agendar(); }
    },

    metas() {
      const aportes = {};
      Banco.todos("aporte").forEach(r => { (aportes[r.dados.meta] = aportes[r.dados.meta] || []).push(r); });
      return Banco.todos("meta").map(r => {
        const o = r.dados;
        return { id: r.id, nome: o.n || "", alvo: +o.alvo || 0, emoji: o.e || "🎯", tipo: o.tipo || "j", sh: r.sh, dono: r.dono,
          atual: Math.max(0, (aportes[r.id] || []).reduce((s, a) => s + (+a.dados.v || 0), 0)) };
      });
    },
    adicionarMeta(nome, alvo, emoji, tipo, sh) { this.novo("meta", sh, { n: nome, alvo, e: emoji, tipo: tipo === "a" ? "a" : "j" }); },
    aportar(metaId, valor, trocaId) {
      const m = Banco.um(metaId); if (!m || m.apagado) return false;
      const d = { meta: metaId, v: valor, q: agora() }; if (trocaId) d.troca = trocaId;
      this.novo("aporte", m.sh, d); return true;
    },

    referencias() {
      const me = this.eu();
      const minhas = Banco.todos("ref").filter(r => r.dono === me);
      if (!minhas.length && !Banco.existeTipoDoDono("ref", me)) {
        this.novo("ref", false, { n: "Café da manhã", e: "☕", v: 3.5, dest: true, evitar: false, m: "" });
        return Banco.todos("ref").filter(r => r.dono === me);
      }
      return minhas;
    },
    salvarReferencia(id, nome, emoji, valor, dest, evitar, meta) {
      const d = { n: nome, e: emoji, v: valor, dest, evitar, m: meta };
      const r = id ? Banco.um(id) : null;
      if (r && r.dono === this.eu()) { Banco.salvar(Object.assign(copia(r), { dados: d })); Nuvem.agendar(); }
      else this.novo("ref", false, d);
    },
    trocas() { const me = this.eu(); return Banco.todos("troca").filter(r => r.dono === me); },
    registrarTroca(refId) {
      const r = Banco.um(refId); if (!r || r.apagado) return null;
      const o = r.dados, v = +o.v || 0;
      const meta = this.metas().find(m => m.id === o.m);
      const trocaId = this.novo("troca", false, { q: agora(), r: refId, v, n: o.n || "", e: o.e || "", m: meta ? meta.id : "" });
      if (meta) this.aportar(meta.id, v, trocaId);
      const destino = meta ? (meta.tipo === "a" ? " abatido em " : " para ") + meta.nome : " poupado";
      return "Evitou " + (o.e || "") + " " + (o.n || "") + ": " + Formato.moeda(v) + destino;
    },
    apagarTroca(id, restaurarEla) {
      if (restaurarEla) this.restaurar(id); else this.apagar(id);
      const me = this.eu();
      Banco.todosComApagados("aporte").filter(r => r.dono === me && r.dados.troca === id)
        .forEach(r => restaurarEla ? this.restaurar(r.id) : this.apagar(r.id));
    }
  };

  // ================= Estado para a interface =================
  function estado() {
    const me = Sessao.eu();
    const porAlvo = lista => { const m = {}; lista.forEach(r => { (m[r.dados.alvo] = m[r.dados.alvo] || []).push(r); }); return m; };
    const curtidas = porAlvo(Armazem.curtidas()), coments = porAlvo(Armazem.comentarios());
    const nomeParc = Sessao.parceiroNome() || "Parceiro(a)";

    const gastos = Armazem.lancamentos().slice(0, 5000).map(l => {
      const cs = curtidas[l.id] || [];
      const cm = (coments[l.id] || []).slice().sort((a, b) => (a.dados.q || 0) - (b.dados.q || 0)).map(c => ({
        id: c.id, t: c.dados.t || "", q: c.dados.q || 0, a: c.dono === me ? Ajustes.nome() : nomeParc, meu: c.dono === me
      }));
      return { id: l.id, q: l.q, v: l.v, d: l.d, c: l.c, t: l.receita ? "r" : "d", sh: l.sh, dono: l.dono, cf: l.cf,
        l: cs.some(x => x.dono === me), lc: cs.length, cm };
    });

    const hoje = Datas.diaHoje();
    const contas = Armazem.contas().map(c => {
      const ocs = Armazem.ocorrenciasComPagamento(c);
      const aberta = ocs.find(x => !x[1]);
      const ref = aberta || ocs[ocs.length - 1];
      const pagador = ocs.find(x => x[1]);
      return {
        id: c.id, n: c.nome, v: c.valor, dia: c.dia, e: c.entrada, ini: c.ini, vezes: c.vezes, var: c.variavel, c: c.categoria,
        freq: c.freq, d0: c.d0, pm: c.pm,
        ocs: ocs.map(([o, p]) => ({ chave: o.chave, dia: o.data.getUTCDate(), n: o.n, paga: !!p, pagaPor: p ? p.dono : "" })),
        parcela: ref ? ref[0].n : 0, sh: c.sh, dono: c.dono,
        paga: ocs.length > 0 && !aberta, pagaPor: pagador ? pagador[1].dono : "",
        diasAte: (ref ? ref[0].data.getUTCDate() : Datas.diaEfetivo(c.dia)) - hoje
      };
    });

    const metas = Armazem.metas().map(m => ({ id: m.id, n: m.nome, alvo: m.alvo, atual: m.atual, e: m.emoji, tipo: m.tipo, sh: m.sh, dono: m.dono }));
    const refs = Armazem.referencias().map(r => ({ id: r.id, n: r.dados.n || "", e: r.dados.e || "", v: +r.dados.v || 0,
      dest: !!r.dados.dest, evitar: !!r.dados.evitar, m: r.dados.m || "" }));
    const trocas = Armazem.trocas().sort((a, b) => (b.dados.q || 0) - (a.dados.q || 0)).map(t => ({
      id: t.id, q: t.dados.q || 0, r: t.dados.r || "", v: +t.dados.v || 0, n: t.dados.n || "", e: t.dados.e || "", m: t.dados.m || "" }));

    return {
      eu: { id: me, nome: Ajustes.nome() },
      parceiro: Sessao.parceiroId() ? { id: Sessao.parceiroId(), nome: nomeParc } : null,
      nuvem: {
        configurada: Sessao.configurada(), logado: Sessao.logado(), email: Sessao.email(), casal: Sessao.casal(),
        codigo: Sessao.codigo(), ultimaMs: Sessao.ultimaSyncMs(), pendentes: Banco.contarSujos(), erro: Sessao.erro(),
        convitePendente: Sessao.convitePendente(), url: Sessao.url()
      },
      moeda: Ajustes.moeda(), gastos, contas, metas, refs, trocas,
      categorias: Categorias.todas, categoriasReceita: Categorias.receitas,
      hoje, diasNoMes: Datas.diasNoMes(), conquistasVistas: Ajustes.conquistas(),
      notif: false, agitar: false, sens: 1, premium: Armazem.premium(), bateriaLivre: true,
      pro: { servidor: Sessao.proServidor(), admin: Sessao.proAdmin(), expiraMs: Sessao.proExpira(), pendente: Sessao.proPendente() },
      atualizacao: Atualizacao.estado(),
      servidorProprio: Sessao.url() !== URL_PADRAO,
      versao: INFO.versao, plataforma: INFO.plataforma
    };
  }
  const resp = (ok, msg) => JSON.stringify({ ok, msg: msg || "", estado: estado() });
  function mudar(f) { f(); depoisDeMudar(); return resp(true); }
  function depoisDeMudar() { Lembretes.agendar(); }

  // ================= Rede =================
  function motivoFalha(detalhe) {
    if (/abort|timeout/i.test(detalhe)) return "O servidor demorou a responder. Tente de novo; se o projeto do Supabase estava pausado, ele acorda em 1 a 2 minutos.";
    if (/Load failed|Failed to fetch|NetworkError|network connection/i.test(detalhe)) return "Sem ligação ao servidor. Confira a internet do celular.";
    return "Falha de ligação (" + detalhe + ")";
  }
  function Resp(codigo, corpo) {
    return {
      codigo, corpo, get ok() { return codigo >= 200 && codigo < 300; },
      json() { try { return JSON.parse(corpo) || {}; } catch (e) { return {}; } },
      mensagem() {
        const j = this.json();
        const m = j.msg || j.error_description || j.message || j.error || "";
        if (codigo === 0) return motivoFalha(corpo);
        if (/Invalid login/i.test(m)) return "E-mail ou senha incorretos";
        if (/already registered/i.test(m)) return "Este e-mail já tem conta. Use Entrar.";
        if (/Email not confirmed/i.test(m)) return "Confirme o e-mail (veja a sua caixa de entrada) e tente de novo";
        if (/Password should/i.test(m)) return "A senha precisa de pelo menos 6 caracteres";
        return m || ("Erro " + codigo);
      }
    };
  }
  async function http(metodo, url, cab, corpo) {
    const ctl = new AbortController();
    const t = setTimeout(() => ctl.abort(), 20000);
    try {
      const h = Object.assign({}, cab);
      if (corpo != null) h["Content-Type"] = "application/json";
      const r = await fetch(url, { method: metodo, headers: h, body: corpo != null ? corpo : undefined, signal: ctl.signal, cache: "no-store" });
      return Resp(r.status, await r.text());
    } catch (e) {
      return Resp(0, (e && e.name === "AbortError") ? "timeout" : String(e && e.message || e));
    } finally { clearTimeout(t); }
  }
  const b64enc = s => btoa(unescape(encodeURIComponent(s))).replace(/\+/g, "-").replace(/\//g, "_");
  const b64dec = s => { s = s.replace(/-/g, "+").replace(/_/g, "/"); while (s.length % 4) s += "="; return decodeURIComponent(escape(atob(s))); };

  // fila única: uma operação de rede por vez (como o executor do Android)
  let fila = Promise.resolve();
  const executar = f => { fila = fila.then(f, f).catch(() => {}); return fila; };
  let timerSync = null;

  const Nuvem = {
    base: () => Sessao.url().replace(/\/+$/, ""),
    cab(token) {
      const chave = Sessao.chave();
      const m = { apikey: chave, Accept: "application/json" };
      if (token) m.Authorization = "Bearer " + token;
      else if (chave.startsWith("eyJ")) m.Authorization = "Bearer " + chave;
      return m;
    },
    configurar(url, chave) {
      let u = String(url || "").replace(/[^\x21-\x7e]/g, "").replace(/\/+$/, "");
      const pm = /supabase\.com\/dashboard\/project\/([a-z0-9]{10,})/.exec(u); if (pm) u = "https://" + pm[1] + ".supabase.co";
      if (/^[a-z0-9]{15,30}$/.test(u)) u = "https://" + u + ".supabase.co";
      if (!u.startsWith("http")) u = "https://" + u;
      const bm = /^(https?:\/\/[^/]+)/.exec(u); if (bm) u = bm[1];
      const local = /^http:\/\/(127\.0\.0\.1|localhost)(:\d+)?$/.test(u);   // servidor de testes
      if (!local) u = u.replace("http://", "https://");
      if ((!u.startsWith("https://") && !local) || u.length < 12) return "Endereço inválido: use o Project URL, como https://xxxx.supabase.co";
      const k = String(chave || "").replace(/[^\x21-\x7e]/g, "");
      if (k.startsWith("sb_secret_")) return "Essa é a chave secreta. Use a chave publishable (sb_publishable_...) ou a anon.";
      if (k.startsWith("eyJ")) { let papel = ""; try { papel = b64dec(k.split(".")[1]); } catch (e) {} if (papel.includes("service_role")) return "Essa é a chave service_role (secreta). Use a chave anon public."; }
      if (k.length < 30) return "Chave incompleta: copie a chave inteira (publishable ou anon)";
      if (Sessao.logado() && u !== Sessao.url()) this.sair();
      Sessao.editar({ url: u, chave: k });
      return null;
    },
    textoConvite(codigo) {
      return "Convite para o app Finanças (código " + codigo + "):\nFINANCAS-" + b64enc(JSON.stringify({ u: Sessao.url(), k: Sessao.chave(), c: codigo }));
    },
    lerConvite(texto) {
      const m = /FINANCAS-([A-Za-z0-9_\-=]+)/.exec(texto || "");
      if (m) {
        let j; try { j = JSON.parse(b64dec(m[1])); } catch (e) { return "Convite inválido"; }
        const e = this.configurar(j.u, j.k); if (e) return e;
        Sessao.editar({ convitePendente: j.c || "" });
        return null;
      }
      const codigo = String(texto || "").trim().toUpperCase();
      if (/^[A-F0-9]{6}$/.test(codigo)) {
        if (!Sessao.configurada()) return "Cole o convite completo (o texto que começa com FINANCAS-)";
        Sessao.editar({ convitePendente: codigo });
        return null;
      }
      return "Convite inválido";
    },
    async testar() {
      const r = await http("GET", this.base() + "/auth/v1/settings", this.cab());
      if (r.ok) return null;
      if (r.codigo === 0) return r.mensagem();
      if (r.codigo === 401 || r.codigo === 403) return "O servidor respondeu, mas recusou a chave. Copie de novo a chave publishable (ou anon).";
      if (r.codigo === 404) return "Endereço errado: o servidor respondeu, mas não é o Supabase. Use o Project URL.";
      return "Servidor respondeu com erro " + r.codigo + ": " + r.mensagem();
    },
    guardarSessao(j) {
      const user = j.user || {};
      const e = { access: j.access_token || "", refresh: j.refresh_token || "", expira: agora() + (j.expires_in || 3600) * 1000 };
      if (user.id) e.uid = user.id;
      if (user.email) e.email = user.email;
      Sessao.editar(e);
    },
    async entrar(email, senha, criar, nome) {
      const corpo = { email: String(email || "").trim(), password: senha };
      let r;
      if (criar) { corpo.data = { nome }; r = await http("POST", this.base() + "/auth/v1/signup", this.cab(), JSON.stringify(corpo)); }
      else r = await http("POST", this.base() + "/auth/v1/token?grant_type=password", this.cab(), JSON.stringify(corpo));
      if (!r.ok) return r.mensagem();
      const j = r.json();
      if (!j.access_token) return "Conta criada. Confirme o e-mail que o Supabase enviou e depois toque em Entrar.";
      return this.aposLogin(j, nome);
    },
    async aposLogin(j, nome) {
      const anterior = Sessao.eu();
      this.guardarSessao(j);
      const uid = Sessao.uid(); if (!uid) return "Resposta sem usuário";
      const nomeConta = ((j.user || {}).user_metadata || {}).nome || "";
      if (nome && nome.trim()) Ajustes.salvarNome(nome.trim()); else if (nomeConta.trim()) Ajustes.salvarNome(nomeConta);
      if (anterior === "local" && !Banco.existeTipoDoDono("lanc", "local")) Banco.apagarTudoDoDono("local");
      if (anterior !== uid) Banco.trocarDono(anterior, uid);
      Sessao.editar({ ultima: null, erro: null });
      await this.sincronizarAgora();
      return null;
    },
    async recuperarSenha(email) {
      if (!String(email || "").includes("@")) return "Escreva o e-mail da conta";
      const r = await http("POST", this.base() + "/auth/v1/recover", this.cab(), JSON.stringify({ email: email.trim() }));
      return r.ok ? null : r.mensagem();
    },
    async redefinirSenha(email, codigo, nova) {
      if ((nova || "").length < 6) return "A senha nova precisa de pelo menos 6 caracteres";
      const r = await http("POST", this.base() + "/auth/v1/verify", this.cab(),
        JSON.stringify({ type: "recovery", email: String(email || "").trim(), token: String(codigo || "").replace(/\D/g, "") }));
      if (!r.ok) return r.codigo >= 400 && r.codigo < 500 ? "Código inválido ou expirado. Peça outro." : r.mensagem();
      const j = r.json();
      if (!j.access_token) return "O servidor não devolveu a sessão. Peça outro código.";
      this.guardarSessao(j);
      const e = await this.trocarSenha(nova); if (e) return e;
      return this.aposLogin(j, "");
    },
    async trocarSenha(nova) {
      if ((nova || "").length < 6) return "A senha nova precisa de pelo menos 6 caracteres";
      const t = await this.token(); if (!t) return "Entre na conta primeiro";
      const r = await http("PUT", this.base() + "/auth/v1/user", this.cab(t), JSON.stringify({ password: nova }));
      if (r.ok) return null;
      return /different from the old/i.test(r.mensagem()) ? "A senha nova tem de ser diferente da atual" : r.mensagem();
    },
    sair() {
      Sessao.sair();
      Banco.apagarTudo();
      avisarTela();
    },
    async token() {
      if (!Sessao.logado()) return null;
      if (agora() < Sessao.expira() - 60000 && Sessao.access()) return Sessao.access();
      const r = await http("POST", this.base() + "/auth/v1/token?grant_type=refresh_token", this.cab(), JSON.stringify({ refresh_token: Sessao.refresh() }));
      if (!r.ok) { if (r.codigo >= 400 && r.codigo < 500) Sessao.editar({ erro: "Sessão expirada. Entre de novo." }); return null; }
      this.guardarSessao(r.json());
      return Sessao.access();
    },
    async rpc(funcao, args) {
      const t = await this.token(); if (!t) return Resp(401, '{"message":"Faça login"}');
      return http("POST", this.base() + "/rest/v1/rpc/" + funcao, this.cab(t), JSON.stringify(args));
    },
    async atualizarPro() {
      const r = await this.rpc("meu_status_pro", {});
      if (r.codigo === 404) { Sessao.editar({ proServidor: false }); return; }
      if (!r.ok) return;
      let o; try { o = JSON.parse(r.corpo)[0]; } catch (e) { return; }
      if (!o) return;
      const exp = o.expira_em ? Date.parse(o.expira_em) || 0 : 0;
      Sessao.editar({ proServidor: true, proExpira: exp, proAdmin: !!o.is_admin, proPendente: !!o.pedido_pendente });
    },
    async lerConfig() {
      const t = await this.token(); if (!t) return Resp(401, '{"message":"Faça login"}');
      return http("GET", this.base() + "/rest/v1/config_app?select=chave,valor,descricao&order=chave", this.cab(t));
    },
    async salvarConfig(chave, valor) {
      const t = await this.token(); if (!t) return Resp(401, '{"message":"Faça login"}');
      return http("POST", this.base() + "/rest/v1/config_app?on_conflict=chave",
        Object.assign(this.cab(t), { Prefer: "resolution=merge-duplicates,return=minimal" }), JSON.stringify({ chave, valor }));
    },
    async convidar() {
      const r = await this.rpc("criar_casal", { p_nome: Ajustes.nome() });
      if (!r.ok) return [null, r.mensagem()];
      const codigo = r.corpo.trim().replace(/^"|"$/g, "");
      Sessao.editar({ codigo });
      await this.atualizarCasal();
      return [this.textoConvite(codigo), null];
    },
    async entrarNoCasal(codigo, sincronizarDepois) {
      const r = await this.rpc("entrar_casal", { p_codigo: codigo, p_nome: Ajustes.nome() });
      if (!r.ok) return r.mensagem();
      const res = r.corpo.trim().replace(/^"|"$/g, "");
      Sessao.editar(res === "ok" ? { convitePendente: null, ultima: null } : { convitePendente: null });
      const erro = { ok: null, codigo_invalido: "Código do casal não encontrado", casal_completo: "Este casal já tem duas pessoas",
        ja_em_casal: "Você já está noutro casal. Saia dele primeiro." }[res];
      const final = erro === undefined ? "Não foi possível entrar no casal" : erro;
      if (final) Sessao.editar({ erro: final });
      else if (sincronizarDepois !== false) await this.sincronizarAgora();
      return final;
    },
    async sairDoCasal() {
      const r = await this.rpc("sair_casal", {});
      if (!r.ok) return r.mensagem();
      Sessao.editar({ casal: null, codigo: null, parceiroId: null, parceiroNome: null });
      this.limparDoParceiro();
      await this.sincronizarAgora();
      return null;
    },
    definirNome(nome) { if (Sessao.logado()) executar(() => this.rpc("definir_nome", { p_nome: nome })); },
    limparDoParceiro() { const me = Sessao.eu(); Banco.exportar().filter(r => r.dono !== me).forEach(r => Banco.remover(r.id)); },
    async atualizarCasal() {
      const t = await this.token(); if (!t) return;
      const r = await http("GET", this.base() + "/rest/v1/membros?select=casal_id,user_id,nome", this.cab(t));
      if (!r.ok) return;
      let arr; try { arr = JSON.parse(r.corpo); } catch (e) { return; }
      const me = Sessao.uid();
      let casal = "", pid = "", pnome = "";
      (arr || []).forEach(o => { casal = o.casal_id || ""; if (o.user_id !== me) { pid = o.user_id || ""; pnome = o.nome || ""; } });
      const tinha = !!Sessao.parceiroId();
      Sessao.editar({ casal, parceiroId: pid, parceiroNome: pnome });
      if (casal && !Sessao.codigo()) {
        const c = await http("GET", this.base() + "/rest/v1/casais?select=codigo", this.cab(t));
        if (c.ok) try { const x = JSON.parse(c.corpo)[0]; if (x && x.codigo) Sessao.editar({ codigo: x.codigo }); } catch (e) {}
      }
      if (!casal) Sessao.editar({ codigo: null });
      if (tinha && !pid) this.limparDoParceiro();
    },
    agendar(atraso) {
      if (!Sessao.logado()) return;
      clearTimeout(timerSync);
      timerSync = setTimeout(() => executar(() => this.sincronizarAgora()), atraso == null ? 1500 : atraso);
    },
    async sincronizarAgora() {
      if (!Sessao.logado()) return "Sem conta";
      const t = await this.token();
      if (!t) { if (Sessao.erro().startsWith("Sessão expirada")) { avisarTela(); return Sessao.erro(); } return this.semRede(); }
      const me = Sessao.eu();
      if (Sessao.convitePendente()) await this.entrarNoCasal(Sessao.convitePendente(), false);

      // 1. Enviar
      for (;;) {
        const pend = Banco.sujos(200);
        if (!pend.length) break;
        const meus = pend.filter(p => p.reg.dono === me);
        pend.filter(p => p.reg.dono !== me).forEach(p => Banco.limpar(p.reg.id, p.ver));
        if (!meus.length) continue;
        const arr = meus.map(p => ({ id: p.reg.id, tipo: p.reg.tipo, compartilhado: p.reg.sh, dados: p.reg.dados, apagado: p.reg.apagado }));
        const cab = Object.assign(this.cab(t), { Prefer: "resolution=merge-duplicates,return=minimal" });
        const r = await http("POST", this.base() + "/rest/v1/registros?on_conflict=id", cab, JSON.stringify(arr));
        if (!r.ok) return r.codigo === 0 ? this.semRede() : this.erro("Falha ao enviar: " + r.mensagem());
        meus.forEach(p => Banco.limpar(p.reg.id, p.ver));
      }

      await this.atualizarCasal();
      await this.atualizarPro();

      // 2. Receber
      let desde = Sessao.ultima() || "1970-01-01T00:00:00+00:00";
      for (;;) {
        const url = this.base() + "/rest/v1/registros?select=id,dono,tipo,compartilhado,dados,apagado,atualizado" +
          "&atualizado=gt." + encodeURIComponent(desde) + "&order=atualizado.asc&limit=1000";
        const r = await http("GET", url, this.cab(t));
        if (!r.ok) return r.codigo === 0 ? this.semRede() : this.erro("Falha ao receber: " + r.mensagem());
        let arr; try { arr = JSON.parse(r.corpo); } catch (e) { return this.erro("Resposta inválida do servidor"); }
        arr.forEach(o => {
          Banco.aplicarRemoto({ id: o.id, tipo: o.tipo, dono: o.dono, sh: !!o.compartilhado, dados: o.dados || {}, apagado: !!o.apagado }, o.atualizado);
          desde = o.atualizado;
        });
        Sessao.editar({ ultima: desde });
        if (arr.length < 1000) break;
      }
      const e = Sessao.erro();
      const manter = e.startsWith("Código") || e.startsWith("Este casal") || e.startsWith("Você já");
      Sessao.editar(Object.assign({ ultimaMs: agora() }, manter ? {} : { erro: null }));
      Lembretes.agendar();
      avisarTela();
      return null;
    },
    semRede() { this.agendar(60000); return this.erro("Sem internet agora. O que você lançou fica guardado e é enviado sozinho quando a ligação voltar."); },
    erro(msg) { Sessao.editar({ erro: msg }); avisarTela(); return msg; }
  };

  let timerTela = null;
  function avisarTela() {
    clearTimeout(timerTela);
    timerTela = setTimeout(() => { if (window.recarregar) window.recarregar(); }, 0);
  }

  // ================= Atualizações (iOS: chegam pelo SideStore) =================
  const Atualizacao = {
    dados: { disponivel: false, versao: "", erro: "", verificadoMs: 0 },
    estado() { return Object.assign({ notas: "", etapa: "", automatico: false, podeInstalar: true, loja: INFO.plataforma === "ios" }, this.dados); },
    async verificar() {
      if (!INFO.codigo) return false;
      const r = await http("GET", URL_PADRAO + "/storage/v1/object/public/atualizacoes/versao.json?t=" + agora(), {});
      this.dados.verificadoMs = agora();
      if (!r.ok) { this.dados.erro = r.codigo === 400 || r.codigo === 404 ? "Ainda não há nenhuma versão publicada" : "Não foi possível verificar agora"; return false; }
      const j = r.json();
      this.dados.erro = "";
      this.dados.versao = j.versao || "";
      this.dados.disponivel = (+j.codigo || 0) > INFO.codigo;
      return this.dados.disponivel;
    }
  };

  // ================= Lembretes de contas (notificações locais do iPhone) =================
  const Lembretes = {
    timer: null,
    agendar() { if (!IOS) return; clearTimeout(this.timer); this.timer = setTimeout(() => this.enviar(), 800); },
    enviar() {
      if (!Sessao.logado()) { nativo("lembretes", { itens: [] }); return; }
      const itens = [], hoje = Datas.hojeUTC(), me = Sessao.eu();
      const contas = Armazem.contas().filter(c => !c.entrada && (!c.sh || c.dono === me || Sessao.parceiroId()));
      for (let k = 0; k < 2; k++) {
        const h = Datas.hoje(); let a = h.a, m = h.m + k; if (m > 12) { m -= 12; a++; }
        contas.forEach(c => ocorrenciasNoMes(c, a, m).forEach(o => {
          if (Armazem.pagoDe(c.id, o.chave)) return;
          [[3, "vence em 3 dias"], [0, "vence hoje"]].forEach(([antes, quando]) => {
            const d = Datas.somarDias(o.data, -antes);
            if (d < hoje) return;
            const ms = new Date(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate(), 9, 0).getTime();
            if (ms <= agora()) return;
            const nome = c.vezes > 1 ? c.nome + " (" + o.n + "/" + c.vezes + ")" : c.nome;
            itens.push({ id: c.id + "|" + o.chave + "|" + antes, quando: ms, titulo: "Conta: " + nome, texto: Formato.moeda(c.valor) + " — " + quando });
          });
        }));
      }
      itens.sort((x, y) => x.quando - y.quando);
      nativo("lembretes", { itens: itens.slice(0, 60) });
    }
  };

  // ================= Backup =================
  const Backup = {
    exportar() {
      const me = Sessao.eu();
      return JSON.stringify({ app: "financas", versao: 3, data: agora(), nome: Ajustes.nome(), moeda: Ajustes.moeda(),
        registros: Banco.exportar().filter(r => r.dono === me) }, null, 2);
    },
    importar(texto) {
      let o; try { o = JSON.parse(texto); } catch (e) { return false; }
      if (!o || o.app !== "financas" || !Array.isArray(o.registros)) return false;
      const me = Sessao.eu();
      const donoOriginal = (o.registros[0] || {}).dono || me;
      Banco.apagarTudoDoDono(me);
      o.registros.forEach(r => { if (r.dono === donoOriginal) Banco.salvar({ id: r.id, tipo: r.tipo, dono: me, sh: !!r.sh, dados: r.dados || {}, apagado: !!r.apagado }); });
      if (o.nome) Ajustes.salvarNome(o.nome);
      if (o.moeda) Ajustes.salvarMoeda(o.moeda);
      Nuvem.agendar(0);
      return true;
    }
  };

  // ================= Chamadas assíncronas (respondem por window.respostaNuvem) =================
  function emSegundoPlano(pedido, f) {
    executar(async () => {
      let r;
      try { r = await f(); } catch (e) { r = { ok: false, msg: (e && e.message) || "Erro" }; }
      r.estado = estado();
      if (window.respostaNuvem) window.respostaNuvem(pedido, r);
    });
  }
  const ok = e => ({ ok: !e, msg: e || "" });

  function avisar(msg) {
    if (typeof window.aviso === "function") window.aviso(msg);
    else if (IOS) nativo("avisar", { msg });
  }

  // ================= window.App =================
  window.App = {
    estado: () => JSON.stringify(estado()),

    adicionarGasto(texto, categoria, receita, sh, quando) {
      const g = Interpretador.interpretar(texto, receita); if (!g) return resp(false);
      if (categoria && categoria.trim()) g.categoria = categoria;
      const q = parseInt(quando); if (!isNaN(q) && String(quando).trim()) g.quando = q;
      return mudar(() => Armazem.adicionar(g, sh));
    },
    detectar(texto, receita) {
      const g = Interpretador.interpretar(texto, receita); if (!g) return "";
      return JSON.stringify({ c: g.categoria, r: g.receita, v: g.valor, e: g.escrita, d: g.descricao });
    },
    editarGasto(id, q, valor, desc, cat, receita, sh) {
      const v = Interpretador.paraNumero(valor);
      if (v === null || v <= 0 || !String(desc || "").trim()) return resp(false);
      return mudar(() => Armazem.editar(id, { quando: parseInt(q), valor: v, descricao: desc.trim(), categoria: cat, receita: !!receita }, !!sh));
    },
    editarConta(id, nome, valor, cat, dia) {
      const v = Interpretador.paraNumero(valor);
      if (v === null || v <= 0 || !String(nome || "").trim()) return resp(false);
      return mudar(() => Armazem.editarConta(id, nome.trim(), v, cat, +dia || 0));
    },
    duplicarGasto: id => mudar(() => Armazem.duplicar(id)),
    apagar: id => mudar(() => Armazem.apagar(id)),
    restaurar: id => mudar(() => Armazem.restaurar(id)),
    mudarCategoria: (id, c) => mudar(() => Armazem.mudarCategoria(id, c)),
    curtir: id => { Armazem.curtir(id); return resp(true); },
    comentar(id, texto) { if (!String(texto || "").trim()) return resp(false); Armazem.comentar(id, texto.trim()); return resp(true); },

    adicionarRecorrente(texto, categoria, receita, sh, d0, freq, vezes, variavel, pagoJa) {
      vezes = parseInt(vezes) || 0;
      if (vezes !== 1 && !Armazem.premium()) return resp(false, "Repetir contas é um recurso Premium");
      const g = Interpretador.interpretar(texto, receita); if (!g) return resp(false, "Escreva o valor e o que é, como “400 renda”");
      const data = Datas.parse(d0); if (!data) return resp(false, "Confira a data");
      if (vezes < 0 || vezes > 600) return resp(false, "Confira o número de vezes");
      const f = freq === "q" || freq === "s" ? freq : "m";
      const nome = g.descricao.charAt(0).toUpperCase() + g.descricao.slice(1);
      const cat = categoria && categoria.trim() ? categoria : (receita ? g.categoria : (g.categoria !== OUTROS ? g.categoria : ""));
      const ini = data.getUTCFullYear() + "-" + pad(data.getUTCMonth() + 1);
      const compartilhar = !!sh && Armazem.premium();
      const id = Armazem.adicionarConta(nome, g.valor, data.getUTCDate(), !!receita, compartilhar, ini, vezes, !!variavel, cat, f, f === "m" ? "" : d0);
      if (pagoJa && data <= Datas.hojeUTC() && ini === Datas.mesAtual()) Armazem.alternarPaga(id, null, f === "m" ? ini : d0);
      depoisDeMudar();
      return resp(true);
    },
    definirPremium: ativo => mudar(() => Armazem.definirPremium(!!ativo)),
    enviarFeedback(tipo, texto) {
      if (!String(texto || "").trim()) return resp(false, "Escreva a sua mensagem");
      Armazem.novo("feedback", false, { tipo, t: String(texto).trim().slice(0, 4000), q: agora(), versao: INFO.versao,
        aparelho: navigator.userAgent.slice(0, 160), sistema: INFO.plataforma });
      return resp(true);
    },
    copiar(texto) { if (IOS) nativo("copiar", { texto }); else if (navigator.clipboard) navigator.clipboard.writeText(texto); return resp(true, "Copiado"); },
    alternarPaga(id, valorReal, chave) {
      const v = Interpretador.paraNumero(valorReal);
      const r = Armazem.alternarPaga(id, v != null && v > 0 ? v : null, chave || "");
      depoisDeMudar();
      return resp(r, r ? "" : "Só quem marcou pode desmarcar");
    },

    adicionarMeta(nome, alvo, emoji, tipo, sh) {
      const a = Interpretador.paraNumero(alvo);
      if (!String(nome || "").trim() || a === null || a <= 0) return resp(false);
      return mudar(() => Armazem.adicionarMeta(nome.trim(), a, (emoji || "").trim() || "🎯", tipo, !!sh));
    },
    aportarMeta(id, valor) {
      const v = Interpretador.paraNumero(String(valor || "").replace("-", "")); if (v === null) return resp(false);
      const sinal = String(valor || "").trim().startsWith("-") ? -1 : 1;
      return resp(Armazem.aportar(id, v * sinal));
    },
    salvarReferencia(id, nome, emoji, valor, destaque, evitar, meta) {
      const v = Interpretador.paraNumero(valor);
      if (!String(nome || "").trim() || v === null || v <= 0) return resp(false);
      return mudar(() => Armazem.salvarReferencia(id, nome.trim(), (emoji || "").trim() || "⭐", v, !!destaque, !!evitar, meta || ""));
    },
    evitei(id) { const msg = Armazem.registrarTroca(id); if (!msg) return resp(false); depoisDeMudar(); return resp(true, msg); },
    apagarTroca: (id, restaurar) => mudar(() => Armazem.apagarTroca(id, !!restaurar)),

    configurarServidor(url, chave) { const e = Nuvem.configurar(url, chave); return resp(!e, e || ""); },
    usarConvite(texto) { const e = Nuvem.lerConvite(texto); if (!e && Sessao.logado()) Nuvem.agendar(0); return resp(!e, e || ""); },
    entrarConta: (email, senha, nome, criar) => emSegundoPlano("entrar", async () => {
      const e = await Nuvem.entrar(email, senha, !!criar, nome || "");
      if (!e && nome && nome.trim()) Nuvem.definirNome(nome.trim());
      return ok(e);
    }),
    recuperarSenha: email => emSegundoPlano("recuperar", async () => ok(await Nuvem.recuperarSenha(email))),
    redefinirSenha: (email, codigo, nova) => emSegundoPlano("redefinir", async () => ok(await Nuvem.redefinirSenha(email, codigo, nova))),
    trocarSenha: nova => emSegundoPlano("trocarSenha", async () => { const e = await Nuvem.trocarSenha(nova); return { ok: !e, msg: e || "Senha alterada" }; }),
    testarServidor: () => emSegundoPlano("testar", async () => { const e = await Nuvem.testar(); return { ok: !e, msg: e || "Ligação ao servidor funcionando" }; }),
    pro: (pedido, funcao, args) => emSegundoPlano(pedido, async () => {
      const permitidas = ["pedir_pro", "cancelar_meu_pedido", "resgatar_codigo", "admin_listar_pedidos", "admin_listar_usuarios",
        "aprovar_pedido", "recusar_pedido", "admin_conceder_dias", "admin_revogar_pro", "admin_gerar_codigo"];
      if (!permitidas.includes(funcao)) return { ok: false, msg: "Função desconhecida" };
      let a = {}; try { a = JSON.parse(args || "{}"); } catch (e) {}
      const r = await Nuvem.rpc(funcao, a);
      if (r.ok) await Nuvem.atualizarPro();
      return { ok: r.ok, msg: r.ok ? "" : r.mensagem(), dados: r.corpo };
    }),
    atualizarPro: pedido => emSegundoPlano(pedido, async () => { await Nuvem.atualizarPro(); return { ok: true }; }),
    lerConfig: pedido => emSegundoPlano(pedido, async () => { const r = await Nuvem.lerConfig(); return { ok: r.ok, msg: r.ok ? "" : r.mensagem(), dados: r.corpo }; }),
    salvarConfig: (pedido, chave, valor) => emSegundoPlano(pedido, async () => {
      let v; try { v = JSON.parse(valor); } catch (e) { return { ok: false, msg: "JSON inválido" }; }
      const r = await Nuvem.salvarConfig(chave, v);
      return { ok: r.ok, msg: r.ok ? "Configuração salva" : (r.codigo === 401 || r.codigo === 403 ? "Só o admin pode alterar" : r.mensagem()) };
    }),
    convidar: () => emSegundoPlano("convidar", async () => { const [t, e] = await Nuvem.convidar(); return { ok: !!t, msg: e || "", texto: t || "" }; }),
    sairDoCasal: () => emSegundoPlano("sairCasal", async () => ok(await Nuvem.sairDoCasal())),
    sincronizar: () => emSegundoPlano("sincronizar", async () => ok(await Nuvem.sincronizarAgora())),
    sairConta() { Nuvem.sair(); Lembretes.agendar(); return resp(true); },

    procurarAtualizacao: () => emSegundoPlano("procurarAtualizacao", async () => {
      const nova = await Atualizacao.verificar();
      const e = Atualizacao.dados.erro;
      return { ok: !e, msg: e || (nova ? "" : "Você já tem a versão mais recente"), nova };
    }),
    instalarAtualizacao: () => emSegundoPlano("instalarAtualizacao", async () => {
      if (IOS) nativo("abrirSideStore");
      return { ok: false, msg: "Abra o SideStore e toque em Atualizar no Finanças" };
    }),
    definirAutoAtualizar: () => resp(true),
    copiarEsquema() {
      fetch("esquema.sql").then(r => r.text()).then(sql => {
        if (IOS) nativo("copiar", { texto: sql });
        else if (navigator.clipboard) navigator.clipboard.writeText(sql);
      }).catch(() => {});
      return resp(true, "Script copiado");
    },
    servidorPadrao() { if (Sessao.url() !== URL_PADRAO) { Nuvem.sair(); Sessao.editar({ url: null, chave: null }); } return resp(true); },

    salvarNome(nome) { Ajustes.salvarNome(nome); Nuvem.definirNome((nome || "").trim()); return resp(true); },
    salvarMoeda: c => mudar(() => Ajustes.salvarMoeda(c)),
    definirSensibilidade: () => resp(true),
    marcarConquista: id => Ajustes.marcarConquista(id),

    compartilhar(texto) {
      if (IOS) nativo("compartilhar", { texto });
      else if (navigator.share) navigator.share({ text: texto }).catch(() => {});
      else if (navigator.clipboard) { navigator.clipboard.writeText(texto); avisar("Convite copiado"); }
    },
    exportar() {
      const txt = Backup.exportar();
      const nome = "financas-backup-" + new Date().toISOString().slice(0, 10) + ".json";
      if (IOS) { nativo("exportar", { nome, texto: txt }); return; }
      const a = document.createElement("a");
      a.href = URL.createObjectURL(new Blob([txt], { type: "application/json" })); a.download = nome; a.click();
    },
    importar() {
      if (IOS) { nativo("importar"); return; }
      const i = document.createElement("input"); i.type = "file"; i.accept = ".json,application/json";
      i.onchange = () => { const f = i.files[0]; if (f) f.text().then(window.__importarBackup); };
      i.click();
    },
    vibrar() { if (IOS) nativo("vibrar"); else if (navigator.vibrate) navigator.vibrate(8); },
    avisar,
    fechar() {},
    // recursos só do Android: sem efeito aqui
    ativarNotificacao() {}, ativarAgitar() {}, abrirAjustesPopup() {}, abrirAjustesApp() { nativo("abrirAjustes"); },
    liberarBateria() {}, permitirInstalacao() {}, abrirInicioAutomatico() {}, testarPopup() {}
  };

  // ================= Entradas vindas do sistema (Siri, atalhos, backup) =================
  /** Lançamentos feitos pela Siri/Atalhos com o app fechado: [{texto, q}]. */
  window.__lancarPendentes = function (lista) {
    let n = 0, ultimo = "";
    (lista || []).forEach(p => {
      const g = Interpretador.interpretar(p.texto, false);
      if (!g) return;
      if (p.q) g.quando = p.q;
      Armazem.adicionar(g, false); n++;
      ultimo = (g.receita ? "+" : "") + Formato.moeda(g.valor) + " " + g.descricao + " (" + g.categoria + ")";
    });
    if (n) { depoisDeMudar(); avisarTela(); setTimeout(() => avisar(n === 1 ? "Lançado pela Siri: " + ultimo : n + " lançamentos da Siri adicionados"), 300); }
    return true;
  };
  window.__importarBackup = function (texto) {
    if (Backup.importar(texto)) { avisarTela(); setTimeout(() => avisar("Backup restaurado"), 300); }
    else avisar("Esse arquivo não é um backup do Finanças");
  };
  /** Chamado ao voltar para o app: sincroniza e confere atualizações. */
  window.__aoAtivar = function () {
    if (Sessao.logado()) Nuvem.agendar(0);
    if (INFO.codigo && agora() - Atualizacao.dados.verificadoMs > 6 * 3600e3) Atualizacao.verificar().then(nova => { if (nova) avisarTela(); });
  };

  // sincroniza a cada minuto enquanto o app estiver aberto
  setInterval(() => { if (document.visibilityState !== "hidden" && Sessao.logado()) Nuvem.agendar(0); }, 60000);
  document.addEventListener("visibilitychange", () => { if (document.visibilityState === "visible") window.__aoAtivar(); });
  setTimeout(() => { window.__aoAtivar(); Lembretes.agendar(); }, 1200);

  // exposto para testes
  window.__motor = { Interpretador, Categorias, ocorrenciasNoMes, Conta, Banco, Sessao, Nuvem, Armazem, Datas, Backup, descarregar };
})();
