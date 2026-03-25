import UIKit
import Vision

extension UIImage {
    
    // Função de rotação física (A que você já validou no Concluir Guias)
    func rotate(radians: Float) -> UIImage? {
        var newSize = CGRect(origin: CGPoint.zero, size: self.size).applying(CGAffineTransform(rotationAngle: CGFloat(radians))).integral.size
        newSize.width = floor(newSize.width)
        newSize.height = floor(newSize.height)

        UIGraphicsBeginImageContextWithOptions(newSize, false, self.scale)
        let context = UIGraphicsGetCurrentContext()!
        context.translateBy(x: newSize.width/2, y: newSize.height/2)
        context.rotate(by: CGFloat(radians))
        self.draw(in: CGRect(x: -self.size.width/2, y: -self.size.height/2, width: self.size.width, height: self.size.height))
        let newImage = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        return newImage
    }

    // Ponto de entrada síncrono para o OCR
    func extractTextDataSync() -> [String: Any]? {
        guard let cgImage = self.cgImage else { return nil }
        
        var allLines = [String]()
        var rawText = ""
        
        // Sincronizando a execução do Vision
        let request = VNRecognizeTextRequest { (request, error) in
            guard let observations = request.results as? [VNRecognizedTextObservation], error == nil else { return }
            for observation in observations {
                guard let topCandidate = observation.topCandidates(1).first else { continue }
                let text = topCandidate.string.trimmingCharacters(in: .whitespacesAndNewlines)
                allLines.append(text)
                rawText += text + "\n"
            }
        }
        
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = false
        
        let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
        try? handler.perform([request])
        
        // Se não leu nada de texto, já para por aqui
        if allLines.isEmpty { return nil }

        // --- LÓGICA DE EXTRAÇÃO E REGEX ---
        var extractedData: [String: Any] = [:]
        var cpf: String? = nil
        var rg: String? = nil
        var name: String? = nil
        var gender: String? = nil
        var allDates = [String]()
        
        let cpfPattern = "\\d{3}[\\.\\s]?\\d{3}[\\.\\s]?\\d{3}[-\\s]?\\d{2}"
        let cleanRgPattern = "\\d{1,2}\\.?\\d{3}\\.?\\d{3}-?[0-9X]{1,2}"
        let strictRgPattern = "\\d{1,2}\\.\\d{3}\\.\\d{3}-[0-9X]{1,2}"
        let datePattern = "\\d{2}/\\d{2}/\\d{4}"
        
        func findMatch(pattern: String, in text: String) -> String? {
            if let range = text.range(of: pattern, options: [.regularExpression, .caseInsensitive]) {
                return String(text[range])
            }
            return nil
        }

        // 1. Varredura de CPF, Datas e Sexo
        for line in allLines {
            if cpf == nil, let match = findMatch(pattern: cpfPattern, in: line) {
                cpf = match
            }
            
            let nsString = line as NSString
            if let regex = try? NSRegularExpression(pattern: datePattern, options: []) {
                let results = regex.matches(in: line, options: [], range: NSRange(location: 0, length: nsString.length))
                for result in results {
                    allDates.append(nsString.substring(with: result.range))
                }
            }
            
            if gender == nil {
                if line.localizedCaseInsensitiveContains("MASCULINO") { gender = "M" }
                else if line.localizedCaseInsensitiveContains("FEMININO") { gender = "F" }
            }
        }
        
        // 2. Extração de RG (Estratégia Clean Line)
        for line in allLines {
            let upperLine = line.uppercased()
            if upperLine.contains("REGISTRO GERAL") || upperLine.contains("IDENTIDADE") || upperLine.contains("RG:") || findMatch(pattern: ".*\\bRG\\b.*", in: upperLine) != nil {
                let cleanLine = upperLine.replacingOccurrences(of: " ", with: "")
                if let match = findMatch(pattern: cleanRgPattern, in: cleanLine) {
                    rg = match
                    break
                }
            }
        }
        
        if rg == nil { // Fallback RG
            for line in allLines {
                let cleanLine = line.uppercased().replacingOccurrences(of: " ", with: "")
                if let match = findMatch(pattern: strictRgPattern, in: cleanLine) {
                    rg = match
                    break
                }
            }
        }

        // 3. Busca de Nome
        let ignoreList = ["REPÚBLICA", "FEDERATIVA", "MINISTÉRIO", "NOME", "ASSINATURA", "TITULAR", "IDENTIDADE", "REGISTRO GERAL", "FILIAÇÃO", "LOCAL", "DATA", "VALIDADE", "DOC", "ORIGEM", "CPF", "NATURALIDADE"]
        
        for i in 0..<allLines.count {
            let line = allLines[i]
            let upperLine = line.uppercased()
            if upperLine.contains("NOME") && !upperLine.contains("PAI") && !upperLine.contains("MÃE") {
                var possible = upperLine.replacingOccurrences(of: "NOME DO TITULAR", with: "")
                possible = possible.replacingOccurrences(of: "NOME", with: "").replacingOccurrences(of: ":", with: "").trimmingCharacters(in: .whitespaces)
                
                if possible.count > 5 && !ignoreList.contains(where: { possible.contains($0) }) {
                    name = possible
                    break
                } else if i + 1 < allLines.count {
                    let next = allLines[i+1]
                    if next.count > 5 && !ignoreList.contains(where: { next.uppercased().contains($0) }) {
                        name = next
                        break
                    }
                }
            }
        }

        // 4. Datas (Nascimento vs Expedição)
        var birthDate: String? = nil
        var issueDate: String? = nil
        if !allDates.isEmpty {
            let sorted = Array(Set(allDates)).sorted { d1, d2 in
                let p1 = d1.split(separator: "/"), p2 = d2.split(separator: "/")
                return (p1.count == 3 && p2.count == 3) ? "\(p1[2])\(p1[1])\(p1[0])" < "\(p2[2])\(p2[1])\(p2[0])" : false
            }
            birthDate = sorted.first
            if sorted.count > 1 { issueDate = sorted.last }
        }

        // SINAL DE SUCESSO PARA O LOOP DE ROTAÇÃO:
        // Se achou CPF ou Nome, a orientação está correta.
        if cpf == nil && name == nil { return nil }

        extractedData["rawText"] = rawText
        extractedData["cpf"] = cpf
        extractedData["rg"] = rg
        extractedData["nome"] = name
        extractedData["sexo"] = gender
        extractedData["dataNascimento"] = birthDate
        extractedData["dataEmissao"] = issueDate
        
        return extractedData
    }
}