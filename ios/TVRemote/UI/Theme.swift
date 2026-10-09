import SwiftUI

/// The calm card style of the Android app: grouped rounded cards on black, one accent.
enum Theme {
    static let background = Color.black
    static let card = Color(red: 23 / 255, green: 24 / 255, blue: 28 / 255)
    static let pill = Color(red: 36 / 255, green: 37 / 255, blue: 42 / 255)
    static let text = Color(red: 236 / 255, green: 236 / 255, blue: 240 / 255)
    static let subtext = Color(red: 140 / 255, green: 142 / 255, blue: 150 / 255)
    static let accent = Color(red: 138 / 255, green: 180 / 255, blue: 248 / 255)
    static let danger = Color(red: 240 / 255, green: 120 / 255, blue: 110 / 255)
}

extension View {
    /// A List in the card style.
    func cards() -> some View {
        listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(Theme.background)
            .listRowBackground(Theme.card)
            .environment(\.defaultMinListRowHeight, 52)
    }

    func cardRow() -> some View {
        listRowBackground(Theme.card)
    }
}

/// Title and an optional detail line, like the Android rows.
struct RowText: View {
    let title: String
    var detail: String?
    var color = Theme.text

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.system(size: 16)).foregroundStyle(color)
            if let detail {
                Text(detail).font(.system(size: 13)).foregroundStyle(Theme.subtext)
            }
        }
        .padding(.vertical, 4)
    }
}

/// A row that does something: a "›" at the right.
struct ActionRow: View {
    let title: String
    var detail: String?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                RowText(title: title, detail: detail)
                Spacer()
                Text("›").font(.system(size: 20)).foregroundStyle(Theme.subtext)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .cardRow()
    }
}

/// Equal rounded pills in a row.
struct Pills: View {
    let items: [(String, () -> Void)]

    var body: some View {
        HStack(spacing: 8) {
            ForEach(items.indices, id: \.self) { i in
                Button(action: items[i].1) {
                    Text(items[i].0)
                        .font(.system(size: 15))
                        .foregroundStyle(Theme.text)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .background(Theme.pill, in: RoundedRectangle(cornerRadius: 14))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.vertical, 6)
        .cardRow()
    }
}
