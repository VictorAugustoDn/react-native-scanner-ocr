import UIKit
import VisionKit
import Vision

@available(iOS 13.0, *)
public class DocScanner: NSObject, VNDocumentCameraViewControllerDelegate {
    
    private var viewController: UIViewController?
    private var successHandler: ([[String: Any]]) -> Void
    private var errorHandler: (String) -> Void
    private var cancelHandler: () -> Void
    private var responseType: String
    private var croppedImageQuality: Int
    
    public init(
        _ viewController: UIViewController? = nil,
        successHandler: @escaping ([[String: Any]]) -> Void = {_ in },
        errorHandler: @escaping (String) -> Void = {_ in },
        cancelHandler: @escaping () -> Void = {},
        responseType: String = "imageFilePath",
        croppedImageQuality: Int = 100
    ) {
        self.viewController = viewController
        self.successHandler = successHandler
        self.errorHandler = errorHandler
        self.cancelHandler = cancelHandler
        self.responseType = responseType
        self.croppedImageQuality = croppedImageQuality
    }
    
    public func startScan(
        _ viewController: UIViewController? = nil,
        successHandler: @escaping ([[String: Any]]) -> Void = {_ in },
        errorHandler: @escaping (String) -> Void = {_ in },
        cancelHandler: @escaping () -> Void = {},
        responseType: String? = "imageFilePath",
        croppedImageQuality: Int? = 100
    ) {
        self.viewController = viewController
        self.successHandler = successHandler
        self.errorHandler = errorHandler
        self.cancelHandler = cancelHandler
        self.responseType = responseType ?? "imageFilePath"
        self.croppedImageQuality = croppedImageQuality ?? 100
        
        if (!VNDocumentCameraViewController.isSupported) {
            self.errorHandler("Document scanning is not supported")
            return
        }
        
        DispatchQueue.main.async {
            let cameraVC = VNDocumentCameraViewController()
            cameraVC.delegate = self
            self.viewController?.present(cameraVC, animated: true)
        }
    }
    
    public func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFinishWith scan: VNDocumentCameraScan) {
        var results: [[String: Any]] = []
        
        for i in 0..<scan.pageCount {
            var img = scan.imageOfPage(at: i)
            var ocr: [String: Any]? = nil
            
            // TENTATIVAS DE ROTAÇÃO FÍSICA
            // 1. Original
            ocr = img.extractTextDataSync()
            
            // 2. 90 graus
            if ocr == nil, let rotated = img.rotate(radians: .pi/2) {
                if let res = rotated.extractTextDataSync() { ocr = res; img = rotated }
            }
            // 3. -90 graus
            if ocr == nil, let rotated = img.rotate(radians: -.pi/2) {
                if let res = rotated.extractTextDataSync() { ocr = res; img = rotated }
            }
            // 4. 180 graus
            if ocr == nil, let rotated = img.rotate(radians: .pi) {
                if let res = rotated.extractTextDataSync() { ocr = res; img = rotated }
            }

            guard let data = img.jpegData(compressionQuality: CGFloat(self.croppedImageQuality)/100.0) else { continue }
            
            var uri = ""
            if responseType.lowercased() == "base64" {
                uri = data.base64EncodedString()
            } else {
                let path = FileUtil().createImageFile(i)
                try? data.write(to: path)
                uri = path.absoluteString
            }
            
            results.append([
                "uri": uri,
                "success": ocr != nil,
                "ocrData": ocr as Any
            ])
        }
        
        DispatchQueue.main.async { controller.dismiss(animated: true) }
        self.successHandler(results)
    }
    
    public func documentCameraViewControllerDidCancel(_ controller: VNDocumentCameraViewController) {
        DispatchQueue.main.async { controller.dismiss(animated: true) }
        self.cancelHandler()
    }

    public func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFailWithError error: Error) {
        DispatchQueue.main.async { controller.dismiss(animated: true) }
        self.errorHandler(error.localizedDescription)
    }
}