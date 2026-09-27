import AppIntents

/// "E aí Siri, lançar gasto no Finanças" → "5,50 café". Fica guardado mesmo sem abrir o app.
struct LancarGastoIntent: AppIntent {
    static var title: LocalizedStringResource = "Lançar gasto"
    static var description = IntentDescription("Anota um gasto no Finanças. Diga o valor e o que foi, como “5,50 café”. Para entrada de dinheiro, comece com “mais”.")
    static var openAppWhenRun: Bool = false

    @Parameter(title: "Gasto", requestValueDialog: IntentDialog("Qual foi o gasto? Diga o valor e o que foi."))
    var texto: String

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        var t = texto.trimmingCharacters(in: .whitespacesAndNewlines)
        // a Siri escreve "mais 100 salário": vira "+100 salário"
        if t.lowercased().hasPrefix("mais ") { t = "+" + t.dropFirst(5) }
        guard t.rangeOfCharacter(from: .decimalDigits) != nil else {
            return .result(dialog: "Não percebi o valor. Diga, por exemplo: 5,50 café.")
        }
        Pendentes.adicionar(t)
        Central.shared.tela.entregarPendentes()
        return .result(dialog: "Anotado: \(t)")
    }
}

/// Abre o app direto no lançamento (para o toque traseiro e o Botão de Ação).
struct NovoLancamentoIntent: AppIntent {
    static var title: LocalizedStringResource = "Novo lançamento"
    static var description = IntentDescription("Abre o Finanças pronto para escrever um gasto.")
    static var openAppWhenRun: Bool = true

    @MainActor
    func perform() async throws -> some IntentResult {
        Central.shared.pedirNovo()
        return .result()
    }
}

struct AtalhosFinancas: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: LancarGastoIntent(),
                    phrases: ["Lançar gasto no \(.applicationName)", "Anotar gasto no \(.applicationName)", "Novo gasto no \(.applicationName)"],
                    shortTitle: "Lançar gasto",
                    systemImageName: "plus.circle")
        AppShortcut(intent: NovoLancamentoIntent(),
                    phrases: ["Novo lançamento no \(.applicationName)", "Abrir lançamento no \(.applicationName)"],
                    shortTitle: "Novo lançamento",
                    systemImageName: "square.and.pencil")
    }
}
