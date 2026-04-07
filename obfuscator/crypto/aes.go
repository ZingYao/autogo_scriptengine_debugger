package crypto

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"io"
)

// EncryptAES 使用 AES-GCM 加密数据
// key: 加密密钥，必须是 16、24 或 32 字节长度
// plaintext: 待加密的明文数据
// 返回: Base64 编码的加密数据
func EncryptAES(key []byte, plaintext []byte) (string, error) {
	if len(key) != 16 && len(key) != 24 && len(key) != 32 {
		return "", errors.New("密钥长度必须是 16、24 或 32 字节")
	}

	block, err := aes.NewCipher(key)
	if err != nil {
		return "", err
	}

	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}

	nonce := make([]byte, gcm.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return "", err
	}

	ciphertext := gcm.Seal(nonce, nonce, plaintext, nil)
	return base64.StdEncoding.EncodeToString(ciphertext), nil
}

// DecryptAES 使用 AES-GCM 解密数据
// key: 解密密钥，必须是 16、24 或 32 字节长度
// ciphertextBase64: Base64 编码的加密数据
// 返回: 解密后的明文数据
func DecryptAES(key []byte, ciphertextBase64 string) ([]byte, error) {
	if len(key) != 16 && len(key) != 24 && len(key) != 32 {
		return nil, errors.New("密钥长度必须是 16、24 或 32 字节")
	}

	ciphertext, err := base64.StdEncoding.DecodeString(ciphertextBase64)
	if err != nil {
		return nil, err
	}

	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}

	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}

	nonceSize := gcm.NonceSize()
	if len(ciphertext) < nonceSize {
		return nil, errors.New("密文数据太短")
	}

	nonce, ciphertext := ciphertext[:nonceSize], ciphertext[nonceSize:]
	plaintext, err := gcm.Open(nil, nonce, ciphertext, nil)
	if err != nil {
		return nil, err
	}

	return plaintext, nil
}

// GenerateKey 生成指定长度的随机密钥
// length: 密钥长度，必须是 16、24 或 32 字节
func GenerateKey(length int) ([]byte, error) {
	if length != 16 && length != 24 && length != 32 {
		return nil, errors.New("密钥长度必须是 16、24 或 32 字节")
	}

	key := make([]byte, length)
	if _, err := io.ReadFull(rand.Reader, key); err != nil {
		return nil, err
	}

	return key, nil
}

// KeyToString 将密钥转换为 Base64 字符串
func KeyToString(key []byte) string {
	return base64.StdEncoding.EncodeToString(key)
}

// KeyFromString 从 Base64 字符串解析密钥
func KeyFromString(keyStr string) ([]byte, error) {
	return base64.StdEncoding.DecodeString(keyStr)
}
