import tkinter as tk
from tkinter import ttk, messagebox
from pathlib import Path

class ScrollableFrame(ttk.Frame):
    def __init__(self, container, *args, **kwargs):
        super().__init__(container, *args, **kwargs)
        self.canvas = tk.Canvas(self, highlightthickness=0)
        scrollbar = ttk.Scrollbar(self, orient="vertical", command=self.canvas.yview)
        self.scrollable_frame = ttk.Frame(self.canvas)

        self.scrollable_frame.bind(
            "<Configure>",
            lambda e: self.canvas.configure(scrollregion=self.canvas.bbox("all"))
        )

        self.canvas.create_window((0, 0), window=self.scrollable_frame, anchor="nw")
        self.canvas.configure(yscrollcommand=scrollbar.set)

        self.canvas.pack(side="left", fill="both", expand=True)
        scrollbar.pack(side="right", fill="y")

        # Привязка колесика мыши при наведении на список
        self.canvas.bind('<Enter>', self._bound_to_mousewheel)
        self.canvas.bind('<Leave>', self._unbound_to_mousewheel)

    def _bound_to_mousewheel(self, event):
        self.canvas.bind_all("<MouseWheel>", self._on_mousewheel)

    def _unbound_to_mousewheel(self, event):
        self.canvas.unbind_all("<MouseWheel>")

    def _on_mousewheel(self, event):
        self.canvas.yview_scroll(int(-1 * (event.delta / 120)), "units")


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("AI Studio Repo Restorer")
        self.geometry("900x600")
        self.configure(padx=10, pady=10)

        # --- Секция ввода ---
        input_frame = ttk.Frame(self)
        input_frame.pack(fill=tk.X, pady=(0, 10))

        ttk.Label(input_frame, text="Вставьте текст с изменениями из Git:", font=("Segoe UI", 10, "bold")).pack(anchor=tk.W, pady=(0, 5))
        
        self.text_input = tk.Text(input_frame, height=10, font=("Consolas", 10))
        self.text_input.pack(fill=tk.X, pady=5)

        ttk.Button(input_frame, text="Сгенерировать кнопки", command=self.process_input).pack(anchor=tk.E)

        # --- Секция кнопок (список файлов) ---
        ttk.Label(self, text="Файлы для восстановления:", font=("Segoe UI", 10, "bold")).pack(anchor=tk.W, pady=(10, 5))
        
        # Контейнер со скроллом для списка
        self.list_container = ScrollableFrame(self)
        self.list_container.pack(fill=tk.BOTH, expand=True)

    def process_input(self):
        # Очищаем предыдущий список
        for widget in self.list_container.scrollable_frame.winfo_children():
            widget.destroy()

        raw_text = self.text_input.get("1.0", tk.END).strip()
        if not raw_text:
            return

        # Разбиваем на строки и убираем пустые
        lines = [line.strip() for line in raw_text.split('\n') if line.strip()]

        # Парсим по 3 строки (tag, path, status)
        for i in range(0, len(lines), 3):
            if i + 2 < len(lines):
                file_tag = lines[i]
                file_path = lines[i+1]
                file_status = lines[i+2]
                
                self.create_file_row(file_path, file_status)

    def create_file_row(self, file_path, status):
        row = ttk.Frame(self.list_container.scrollable_frame)
        row.pack(fill=tk.X, pady=4, padx=5)

        # Цветная метка статуса
        status_color = "red" if status == "Deleted" else "blue"
        status_lbl = tk.Label(row, text=f"[{status}]", fg=status_color, font=("Consolas", 10, "bold"), width=10, anchor="w")
        status_lbl.pack(side=tk.LEFT)

        # Имя файла
        ttk.Label(row, text=file_path, font=("Consolas", 10), width=50, anchor="w").pack(side=tk.LEFT, padx=5)

        # Кнопки
        if status == "Deleted":
            # Кнопка копирования пути (нужна только для создания новых файлов)
            btn_path = ttk.Button(row, text="📋 Копировать Путь")
            btn_path.config(command=lambda b=btn_path, p=file_path: self.copy_text(p, b))
            btn_path.pack(side=tk.LEFT, padx=5)

        # Кнопка копирования содержимого (нужна всегда)
        btn_content = ttk.Button(row, text="📄 Копировать Текст")
        btn_content.config(command=lambda b=btn_content, p=file_path: self.copy_file_content(p, b))
        btn_content.pack(side=tk.LEFT, padx=5)

    def copy_text(self, text, button):
        """Копирует текст в буфер обмена и дает визуальный отклик"""
        self.clipboard_clear()
        self.clipboard_append(text)
        self.update() # Обновляем буфер для некоторых ОС
        
        # Визуальный отклик на кнопке
        original_text = button.cget("text")
        button.config(text="✓ Скопировано!")
        self.after(1000, lambda: button.config(text=original_text))

    def copy_file_content(self, relative_path, button):
        """Читает файл с диска и копирует его содержимое"""
        # pathlib автоматически обрабатывает прямые слэши '/' под Windows
        file_path = Path(relative_path) 
        
        if not file_path.exists():
            messagebox.showwarning("Файл не найден", f"Не удалось найти файл по пути:\n{file_path.absolute()}")
            return
            
        try:
            content = file_path.read_text(encoding='utf-8')
            self.copy_text(content, button)
        except Exception as e:
            messagebox.showerror("Ошибка чтения", f"Произошла ошибка при чтении {relative_path}:\n{e}")

if __name__ == "__main__":
    app = App()
    app.mainloop()