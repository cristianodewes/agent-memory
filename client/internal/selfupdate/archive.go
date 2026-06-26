package selfupdate

import (
	"archive/tar"
	"archive/zip"
	"bytes"
	"compress/gzip"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"path"
	"strings"
)

const (
	// projectName is the goreleaser project_name and binary base name (see
	// .goreleaser.yaml). It prefixes every release archive.
	projectName = "agent-memory"
	// checksumsFileName is the goreleaser checksum artifact bundled with each release.
	checksumsFileName = "checksums.txt"
)

// assetFileName returns the goreleaser archive name for goos/goarch at version (the
// version WITHOUT a leading "v"): "agent-memory_<version>_<os>_<arch>.<ext>", a .zip on
// Windows and a .tar.gz elsewhere — mirroring the archives block in .goreleaser.yaml.
func assetFileName(goos, goarch, version string) string {
	ext := "tar.gz"
	if goos == "windows" {
		ext = "zip"
	}
	return fmt.Sprintf("%s_%s_%s_%s.%s", projectName, version, goos, goarch, ext)
}

// innerBinaryName is the binary file name inside the archive: goreleaser appends ".exe"
// on Windows.
func innerBinaryName(goos string) string {
	if goos == "windows" {
		return projectName + ".exe"
	}
	return projectName
}

// extractBinary writes the agent-memory binary contained in the archive (raw bytes of
// the .tar.gz or .zip asset) to a fresh temp file in destDir and returns its path. The
// archive format is chosen from assetName. wantName is the binary file name to extract.
func extractBinary(archive []byte, assetName, destDir, wantName string) (string, error) {
	switch {
	case strings.HasSuffix(assetName, ".zip"):
		return extractFromZip(archive, destDir, wantName)
	case strings.HasSuffix(assetName, ".tar.gz"), strings.HasSuffix(assetName, ".tgz"):
		return extractFromTarGz(archive, destDir, wantName)
	default:
		return "", fmt.Errorf("formato de arquivo não suportado: %s", assetName)
	}
}

func extractFromTarGz(data []byte, destDir, wantName string) (string, error) {
	gz, err := gzip.NewReader(bytes.NewReader(data))
	if err != nil {
		return "", fmt.Errorf("abrindo gzip: %w", err)
	}
	defer gz.Close()

	tr := tar.NewReader(gz)
	for {
		hdr, err := tr.Next()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return "", fmt.Errorf("lendo tar: %w", err)
		}
		if hdr.Typeflag != tar.TypeReg || path.Base(hdr.Name) != wantName {
			continue
		}
		return writeTempBinary(destDir, tr)
	}
	return "", fmt.Errorf("binário %q não encontrado no arquivo", wantName)
}

func extractFromZip(data []byte, destDir, wantName string) (string, error) {
	zr, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		return "", fmt.Errorf("abrindo zip: %w", err)
	}
	for _, f := range zr.File {
		if f.FileInfo().IsDir() || path.Base(f.Name) != wantName {
			continue
		}
		rc, err := f.Open()
		if err != nil {
			return "", fmt.Errorf("abrindo %s no zip: %w", f.Name, err)
		}
		dst, err := writeTempBinary(destDir, rc)
		_ = rc.Close()
		return dst, err
	}
	return "", fmt.Errorf("binário %q não encontrado no arquivo", wantName)
}

// writeTempBinary streams src into a new temp file in destDir (same filesystem as the
// final binary, so the later rename is atomic) and returns its path. On any error the
// partial temp file is removed.
func writeTempBinary(destDir string, src io.Reader) (string, error) {
	tmp, err := os.CreateTemp(destDir, projectName+"-update-*.tmp")
	if err != nil {
		return "", fmt.Errorf("criando arquivo temporário: %w", err)
	}
	if _, err := io.Copy(tmp, src); err != nil {
		_ = tmp.Close()
		_ = os.Remove(tmp.Name())
		return "", fmt.Errorf("escrevendo binário: %w", err)
	}
	if err := tmp.Close(); err != nil {
		_ = os.Remove(tmp.Name())
		return "", fmt.Errorf("finalizando arquivo temporário: %w", err)
	}
	return tmp.Name(), nil
}

// parseChecksums maps each "<sha256hex>  <filename>" line of a goreleaser checksums.txt
// to its file. Fields are whitespace-separated (goreleaser emits two spaces).
func parseChecksums(text string) map[string]string {
	out := map[string]string{}
	for _, line := range strings.Split(text, "\n") {
		fields := strings.Fields(line)
		if len(fields) != 2 {
			continue
		}
		out[fields[1]] = strings.ToLower(fields[0])
	}
	return out
}

func sha256Hex(b []byte) string {
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])
}
