package selfupdate

import (
	"fmt"
	"os"
	"runtime"
)

// renameFunc and removeFunc indirect os.Rename/os.Remove so tests can force the failure
// paths that exercise rollback. Production always uses the stdlib funcs.
var (
	renameFunc = os.Rename
	removeFunc = os.Remove
)

// replaceBinary swaps the file at execPath for the freshly-staged binary at newPath,
// atomically and with rollback.
//
//   - Unix: a same-directory rename is atomic and is allowed even while the binary runs,
//     so we rename newPath over execPath in one step.
//   - Windows: a running image cannot be overwritten, but it CAN be renamed aside. We
//     move the live binary to "<exec>.old", move the new binary into place, then
//     best-effort delete the .old (which may still be locked as the running image — a
//     later run clears it). If installing the new binary fails, we rename .old back.
func replaceBinary(execPath, newPath string) error {
	if runtime.GOOS == "windows" {
		return replaceWindows(execPath, newPath)
	}
	return replaceUnix(execPath, newPath)
}

func replaceUnix(execPath, newPath string) error {
	if err := renameFunc(newPath, execPath); err != nil {
		_ = removeFunc(newPath) // nothing changed; drop the staged binary
		return fmt.Errorf("substituindo o binário: %w", err)
	}
	return nil
}

func replaceWindows(execPath, newPath string) error {
	old := execPath + ".old"
	_ = removeFunc(old) // clear any leftover from a previous update

	// Step 1: move the running binary aside. Windows permits renaming a running image.
	if err := renameFunc(execPath, old); err != nil {
		_ = removeFunc(newPath)
		return fmt.Errorf("movendo o binário atual para %s: %w", old, err)
	}
	// Step 2: move the new binary into place. On failure, roll back step 1.
	if err := renameFunc(newPath, execPath); err != nil {
		if rbErr := renameFunc(old, execPath); rbErr != nil {
			return fmt.Errorf("falha ao instalar o novo binário (%w) e ao restaurar o anterior (%v); "+
				"binário original preservado em %s", err, rbErr, old)
		}
		_ = removeFunc(newPath)
		return fmt.Errorf("instalando o novo binário: %w", err)
	}
	// Step 3: best-effort cleanup; the .old image may still be locked (it is us).
	_ = removeFunc(old)
	return nil
}
