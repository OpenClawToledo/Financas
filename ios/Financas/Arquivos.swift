import Foundation

/// Dados do app guardados em arquivos (Documents/dados). O motor JS lê tudo ao abrir e grava cada mudança.
enum Arquivos {
    static let pasta: URL = {
        let u = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("dados", isDirectory: true)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }()
    private static let fila = DispatchQueue(label: "financas.arquivos")
    static let permitidos: Set<String> = ["registros", "ajustes", "nuvem"]

    static func ler(_ chave: String) -> String? {
        fila.sync { try? String(contentsOf: pasta.appendingPathComponent(chave + ".json"), encoding: .utf8) }
    }

    static func gravar(_ chave: String, _ texto: String) {
        guard permitidos.contains(chave) else { return }
        fila.async {
            try? texto.write(to: pasta.appendingPathComponent(chave + ".json"), atomically: true, encoding: .utf8)
        }
    }

    /// Espera as gravações pendentes terminarem.
    static func esperar() { fila.sync {} }

    static func tudo() -> [String: String] {
        var d: [String: String] = [:]
        for k in permitidos { if let t = ler(k) { d[k] = t } }
        return d
    }
}

/// Lançamentos feitos pela Siri/Atalhos enquanto a tela não estava pronta. O motor JS consome e apaga.
enum Pendentes {
    private static var arquivo: URL { Arquivos.pasta.appendingPathComponent("pendentes.json") }
    private static let fila = DispatchQueue(label: "financas.pendentes")

    static func adicionar(_ texto: String) {
        fila.sync {
            var lista = lerSemFila()
            lista.append(["texto": texto, "q": Int64(Date().timeIntervalSince1970 * 1000)])
            if let d = try? JSONSerialization.data(withJSONObject: lista) { try? d.write(to: arquivo, options: .atomic) }
        }
    }

    /// Devolve e remove todos os pendentes.
    static func retirar() -> [[String: Any]] {
        fila.sync {
            let lista = lerSemFila()
            try? FileManager.default.removeItem(at: arquivo)
            return lista
        }
    }

    private static func lerSemFila() -> [[String: Any]] {
        guard let d = try? Data(contentsOf: arquivo),
              let l = try? JSONSerialization.jsonObject(with: d) as? [[String: Any]] else { return [] }
        return l
    }
}
