/**
 * 字符串转换为unsigned char指针
 */
unsigned char* stringToUnsignedChar(const char* str) {
    size_t length = strlen(str);
    unsigned char* byteArray = (unsigned char*)malloc(length + 1); // 需要额外一个字节用于存储字符串结尾的'\0'
    memcpy(byteArray, str, length + 1); // 复制字符串内容到unsigned char数组
    return byteArray;
}

/**
 * 十六进制转换为byte指针
 */
unsigned char *hexToByteArray(const char *hexString) {
    size_t stringLength = strlen(hexString);
    size_t byteLength = (stringLength + 1) / 2; // Round up to handle odd-length strings
    unsigned char *byteArray = (unsigned char *)malloc(byteLength + 1);

    char *paddedString;

    // Add a '0' character to the beginning if the string length is odd
    if (stringLength % 2 != 0) {
        paddedString = new char[stringLength + 2];
        paddedString[0] = '0';
        strcpy(paddedString + 1, hexString);
        stringLength += 1;
    } else {
        paddedString = new char[stringLength + 1];
        strcpy(paddedString, hexString);
    }

    // 逐个读取16进制字符并转换为unsigned char
    for (size_t i = 0; i < byteLength; i++) {
        unsigned int value;
        sscanf(&paddedString[i * 2], "%2x", &value);
        byteArray[i] = static_cast<unsigned char>(value);
    }
    byteArray[byteLength] = '\0';

    delete[] paddedString;
    return byteArray;
}
// unsigned char* hexToByteArray(const char* hexString) {
//     size_t stringLength = strlen(hexString);
//     int byteLength = (stringLength + 1) / 2; // Round up to handle odd-length strings
//     unsigned char *byteArray = (unsigned char *) malloc(byteLength + 1);
//     // Add a '0' character to the beginning if the string length is odd
//     if (stringLength % 2 != 0) {
//         char* paddedString = new char[stringLength + 2];
//         paddedString[0] = '0';
//         strcpy(paddedString + 1, hexString);
//         hexString = paddedString;
//         stringLength += 1;
//         byteLength += 1;
//     }
//     // 逐个读取16进制字符并转换为unsigned char
//     for (size_t i = 0; i < byteLength; i++) {
//         unsigned int value;
//         sscanf(&hexString[i * 2], "%2x", &value);
//         byteArray[i] = (unsigned char)value;
//     }
//     byteArray[byteLength] = '\0';

//     return byteArray;
// }
// unsigned char* hexToByteArray(const char* hexString) {
//     size_t stringLength = strlen(hexString);
//     int byteLength = stringLength / 2; // 一个16进制字符对应一个字节
//     unsigned char *byteArray = (unsigned char *) malloc(byteLength + 1);
//     // 逐个读取16进制字符并转换为unsigned char
//     for (size_t i = 0; i < byteLength; i++) {
//         unsigned int value;
//         sscanf(&hexString[i * 2], "%2x", &value);
//         byteArray[i] = (unsigned char)value;
//     }
//     byteArray[byteLength] = '\0';
//     return byteArray;
// }

/**
 * 无符号字符转换为字符串
 */
std::string unsignedCharToString(const unsigned char *p) {
    size_t byteArraySize = strlen((const char *) p); // 计算数据长度
    std::string oss;
    for (size_t i = 0; i < byteArraySize; ++i) {
        oss.push_back(static_cast<char>(p[i]));
    }
    return oss;
}

std::string unsignedCharToString(const unsigned char *p, size_t byteArraySize) {
    std::string oss;
    for (size_t i = 0; i < byteArraySize; ++i) {
        if (p[i] >= 32 && p[i] != 127) { // 跳过不可打印的ASCII字符 去除值介于1到31（ASCII控制字符）之间和127（删除字符）的字节
            oss.push_back(static_cast<char>(p[i]));
        }
    }
    return oss;
}

/**
 * 无符号字符串转换为16进制字符串
 */
std::string unsignedCharToHexString(const unsigned char* byteArray) {
    size_t byteArraySize = strlen((const char *) byteArray); // 计算数据长度
    std::stringstream oss;
    for (size_t i = 0; i < byteArraySize; i++) {
        oss << std::hex << std::setw(2) << std::setfill('0') << std::uppercase << static_cast<int>(byteArray[i]);
    }
    return oss.str();
}

/**
 * 无符号字符串转换为16进制字符串
 */
std::string unsignedCharToHexString(const unsigned char* byteArray, size_t byteArraySize) {
    std::stringstream oss;
    for (size_t i = 0; i < byteArraySize; i++) {
        oss << std::hex << std::setw(2) << std::setfill('0') << std::uppercase << static_cast<int>(byteArray[i]);
    }
    return oss.str();
}

/**
 * SM4填充数据补位
 * @param data 待加密数据
 * @param dataLength 数据长度
 * @param blockSize 数据块大小
 */
unsigned char* sm4Padding(unsigned char* data, size_t blockSize) {
    size_t dataLength = strlen(reinterpret_cast<const char *const>(data));
    size_t paddingValue = blockSize - (dataLength % blockSize);
    size_t paddedLength = dataLength + paddingValue;
    auto *p = (unsigned char *) malloc(paddedLength+1);
    memcpy(p, data, dataLength);
    for (size_t i = dataLength; i < paddedLength; i++) {
        p[i] = paddingValue;
    }
    p[paddedLength] = '\0';
    return p;
}
