#ifndef SGR_MOUSE_GENERATOR_H
#define SGR_MOUSE_GENERATOR_H

#include <string>
#include <vector>
#include <cstdint>

class SgrMouseGeneratorNative {
public:
    SgrMouseGeneratorNative();
    ~SgrMouseGeneratorNative();

    void setTrackingEnabled(bool enabled);
    bool isTrackingEnabled() const { return mTrackingEnabled; }

    std::string generateButtonPress(int button, int col, int row, int modifiers = 0);
    std::string generateButtonRelease(int button, int col, int row, int modifiers = 0);
    std::string generateMotion(int button, int col, int row, int modifiers = 0);
    std::string generateWheel(int direction, int col, int row, int modifiers = 0);

    static void pixelToGrid(float pxX, float pxY, int cellW, int cellH, int totalCols, int totalRows, int& outCol, int& outRow);

private:
    bool mTrackingEnabled = false;
};

#endif // SGR_MOUSE_GENERATOR_H
