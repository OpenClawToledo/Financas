import UIKit
import UserNotifications

@main
final class AppDelegate: UIResponder, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    var window: UIWindow?

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        let w = UIWindow(frame: UIScreen.main.bounds)
        w.overrideUserInterfaceStyle = .dark
        w.rootViewController = Central.shared.tela
        w.makeKeyAndVisible()
        window = w
        UNUserNotificationCenter.current().delegate = self
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
        return true
    }

    /// financas://novo abre direto a folha de lançamento.
    func application(_ app: UIApplication, open url: URL, options: [UIApplication.OpenURLOptionsKey: Any] = [:]) -> Bool {
        if url.host == "novo" { Central.shared.pedirNovo() }
        return true
    }

    func applicationDidBecomeActive(_ application: UIApplication) { Central.shared.tela.aoAtivar() }
    func applicationWillResignActive(_ application: UIApplication) { Central.shared.tela.guardarAntesDeSair() }

    // Mostra os lembretes mesmo com o app aberto
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }
}

/// Ponto único que liga o app, a tela e os atalhos da Siri.
final class Central {
    static let shared = Central()
    lazy var tela = Tela()
    private(set) var novoPendente = false

    func pedirNovo() {
        novoPendente = true
        DispatchQueue.main.async { self.tela.abrirNovoSePedido() }
    }
    func novoAtendido() { novoPendente = false }
}
