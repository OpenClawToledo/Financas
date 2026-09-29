import UIKit
import WebKit
import UserNotifications
import UniformTypeIdentifiers

/// A tela do app: a mesma interface HTML do Android, com o motor JS (motor.js) no lugar do Kotlin.
/// Aqui ficam só as coisas que precisam do sistema: guardar arquivos, notificações, partilhar, vibrar.
final class Tela: UIViewController, WKScriptMessageHandler, WKURLSchemeHandler, WKNavigationDelegate, WKUIDelegate, UIDocumentPickerDelegate {
    private var web: WKWebView!
    private var pronta = false
    private let fundo = UIColor(red: 0x0B / 255.0, green: 0x12 / 255.0, blue: 0x10 / 255.0, alpha: 1)

    override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }

    override func loadView() {
        let cfg = WKWebViewConfiguration()
        cfg.setURLSchemeHandler(self, forURLScheme: "app")
        let uc = WKUserContentController()
        uc.add(self, name: "financas")
        uc.addUserScript(WKUserScript(source: scriptInicial(), injectionTime: .atDocumentStart, forMainFrameOnly: true))
        cfg.userContentController = uc
        cfg.dataDetectorTypes = []

        web = WKWebView(frame: .zero, configuration: cfg)
        web.navigationDelegate = self
        web.uiDelegate = self
        web.allowsLinkPreview = false
        web.isOpaque = false
        web.backgroundColor = fundo
        web.scrollView.backgroundColor = fundo
        web.scrollView.bounces = false
        web.scrollView.contentInsetAdjustmentBehavior = .never
        if #available(iOS 16.4, *) { web.isInspectable = true }

        let v = UIView()
        v.backgroundColor = fundo
        web.translatesAutoresizingMaskIntoConstraints = false
        v.addSubview(web)
        NSLayoutConstraint.activate([
            web.leadingAnchor.constraint(equalTo: v.leadingAnchor),
            web.trailingAnchor.constraint(equalTo: v.trailingAnchor),
            web.topAnchor.constraint(equalTo: v.safeAreaLayoutGuide.topAnchor),
            web.bottomAnchor.constraint(equalTo: v.safeAreaLayoutGuide.bottomAnchor)
        ])
        view = v
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        web.load(URLRequest(url: URL(string: "app://financas/index.html")!))
    }

    /// Entrega ao motor JS os dados guardados e a versão do app, antes de qualquer script da página.
    private func scriptInicial() -> String {
        let info = Bundle.main.infoDictionary ?? [:]
        let versao = (info["CFBundleShortVersionString"] as? String) ?? "1.9"
        let build = (info["CFBundleVersion"] as? String) ?? "0"
        let app: [String: Any] = ["versao": "\(versao) (\(build))", "codigo": Int(build) ?? 0, "plataforma": "ios"]
        return "window.__FIN_APP = \(json(app));\nwindow.__FIN_INICIAL = \(json(Arquivos.tudo()));"
    }

    private func json(_ o: Any) -> String {
        guard let d = try? JSONSerialization.data(withJSONObject: o), let s = String(data: d, encoding: .utf8) else { return "{}" }
        return s
    }

    // MARK: - Arquivos da interface (app://financas/...)

    func webView(_ webView: WKWebView, start task: WKURLSchemeTask) {
        guard let url = task.request.url else { return }
        let nome = url.lastPathComponent.isEmpty ? "index.html" : url.lastPathComponent
        let partes = nome.split(separator: ".", maxSplits: 1).map(String.init)
        guard partes.count == 2, let arq = Bundle.main.url(forResource: partes[0], withExtension: partes[1]),
              let dados = try? Data(contentsOf: arq) else {
            let r = HTTPURLResponse(url: url, statusCode: 404, httpVersion: "HTTP/1.1", headerFields: [:])!
            task.didReceive(r); task.didReceive(Data()); task.didFinish()
            return
        }
        let tipos = ["html": "text/html; charset=utf-8", "js": "text/javascript; charset=utf-8", "sql": "text/plain; charset=utf-8", "json": "application/json"]
        let r = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1",
                                headerFields: ["Content-Type": tipos[partes[1]] ?? "application/octet-stream", "Cache-Control": "no-cache"])!
        task.didReceive(r); task.didReceive(dados); task.didFinish()
    }

    func webView(_ webView: WKWebView, stop task: WKURLSchemeTask) {}

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        pronta = true
        entregarPendentes()
        abrirNovoSePedido()
    }

    /// Se o sistema encerrar o processo da página, recarrega (os dados estão nos arquivos).
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        pronta = false
        webView.configuration.userContentController.removeAllUserScripts()
        webView.configuration.userContentController.addUserScript(WKUserScript(source: scriptInicial(), injectionTime: .atDocumentStart, forMainFrameOnly: true))
        webView.load(URLRequest(url: URL(string: "app://financas/index.html")!))
    }

    /// Links externos abrem no Safari.
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        if let u = action.request.url, u.scheme == "http" || u.scheme == "https", action.navigationType == .linkActivated {
            UIApplication.shared.open(u); decisionHandler(.cancel); return
        }
        decisionHandler(.allow)
    }

    // Caixas de alerta/confirmação do JS, caso apareçam
    func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping () -> Void) {
        let a = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        a.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler() })
        present(a, animated: true)
    }

    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (Bool) -> Void) {
        let a = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        a.addAction(UIAlertAction(title: "Cancelar", style: .cancel) { _ in completionHandler(false) })
        a.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler(true) })
        present(a, animated: true)
    }

    // MARK: - Ciclo de vida

    func aoAtivar() {
        guard pronta else { return }
        entregarPendentes()
        abrirNovoSePedido()
        web.evaluateJavaScript("window.__aoAtivar && __aoAtivar()")
    }

    /// Antes de ir para segundo plano: pede ao motor para gravar o que falta e dá tempo para terminar.
    func guardarAntesDeSair() {
        guard pronta else { return }
        var tarefa = UIBackgroundTaskIdentifier.invalid
        tarefa = UIApplication.shared.beginBackgroundTask { UIApplication.shared.endBackgroundTask(tarefa) }
        web.evaluateJavaScript("window.__motor && __motor.descarregar()") { _, _ in
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                Arquivos.esperar()
                UIApplication.shared.endBackgroundTask(tarefa)
            }
        }
    }

    func entregarPendentes() {
        DispatchQueue.main.async {
            guard self.pronta else { return }
            let lista = Pendentes.retirar()
            guard !lista.isEmpty else { return }
            self.web.callAsyncJavaScript("return window.__lancarPendentes(lista)", arguments: ["lista": lista], in: nil, in: .page) { r in
                if case .failure = r { lista.forEach { Pendentes.adicionar(($0["texto"] as? String) ?? "") } }
            }
        }
    }

    func abrirNovoSePedido() {
        guard pronta, Central.shared.novoPendente else { return }
        web.evaluateJavaScript("window.abrirNovo ? abrirNovo() : false") { r, _ in
            if (r as? Bool) == true { Central.shared.novoAtendido() }
        }
    }

    // MARK: - Mensagens do motor JS

    func userContentController(_ uc: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let m = message.body as? [String: Any], let acao = m["acao"] as? String else { return }
        switch acao {
        case "gravar":
            if let k = m["chave"] as? String, let v = m["valor"] as? String { Arquivos.gravar(k, v) }
        case "vibrar":
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        case "compartilhar":
            partilhar([m["texto"] as? String ?? ""])
        case "copiar":
            UIPasteboard.general.string = m["texto"] as? String
        case "exportar":
            let nome = (m["nome"] as? String) ?? "financas-backup.json"
            let u = FileManager.default.temporaryDirectory.appendingPathComponent(nome)
            try? ((m["texto"] as? String) ?? "").write(to: u, atomically: true, encoding: .utf8)
            partilhar([u])
        case "importar":
            let p = UIDocumentPickerViewController(forOpeningContentTypes: [.json, .plainText, .data], asCopy: true)
            p.delegate = self
            present(p, animated: true)
        case "lembretes":
            agendarLembretes((m["itens"] as? [[String: Any]]) ?? [])
        case "abrirSideStore":
            if let u = URL(string: "sidestore://"), UIApplication.shared.canOpenURL(u) { UIApplication.shared.open(u) }
        case "abrirAjustes":
            if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) }
        default:
            break
        }
    }

    private func partilhar(_ itens: [Any]) {
        let a = UIActivityViewController(activityItems: itens, applicationActivities: nil)
        a.popoverPresentationController?.sourceView = view
        a.popoverPresentationController?.sourceRect = CGRect(x: view.bounds.midX, y: view.bounds.maxY - 80, width: 1, height: 1)
        present(a, animated: true)
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let u = urls.first else { return }
        let texto = (try? String(contentsOf: u, encoding: .utf8)) ?? ""
        web.callAsyncJavaScript("window.__importarBackup(texto)", arguments: ["texto": texto], in: nil, in: .page) { _ in }
    }

    /// Troca todos os lembretes de contas pelos que o motor calculou (o iPhone guarda até 64).
    private func agendarLembretes(_ itens: [[String: Any]]) {
        let c = UNUserNotificationCenter.current()
        c.getPendingNotificationRequests { antigos in
            c.removePendingNotificationRequests(withIdentifiers: antigos.map(\.identifier).filter { $0.hasPrefix("conta|") })
            for i in itens.prefix(60) {
                guard let id = i["id"] as? String, let ms = (i["quando"] as? NSNumber)?.doubleValue else { continue }
                let quando = Date(timeIntervalSince1970: ms / 1000)
                guard quando > Date() else { continue }
                let conteudo = UNMutableNotificationContent()
                conteudo.title = (i["titulo"] as? String) ?? "Conta"
                conteudo.body = (i["texto"] as? String) ?? ""
                conteudo.sound = .default
                let comps = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: quando)
                let gatilho = UNCalendarNotificationTrigger(dateMatching: comps, repeats: false)
                c.add(UNNotificationRequest(identifier: "conta|" + id, content: conteudo, trigger: gatilho))
            }
        }
    }
}
