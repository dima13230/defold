// Copyright 2020-2024 The Defold Foundation
// Copyright 2014-2020 King
// Copyright 2009-2014 Ragnar Svensson, Christian Murray
// Licensed under the Defold License version 1.0 (the "License"); you may not use
// this file except in compliance with the License.
// 
// You may obtain a copy of the License, together with FAQs at
// https://www.defold.com/license
// 
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

#ifndef DM_RENDER_FONTVIEW_H
#define DM_RENDER_FONTVIEW_H

#include <stdint.h>

#include <resource/resource.h>

#include <platform/platform_window.h>

#include <graphics/graphics.h>

#include <hid/hid.h>

#include <render/render.h>
#include <render/font_renderer.h>

#include <render/render_ddf.h>

namespace dmFontView
{
    struct Context
    {
        const char* m_TestString;
        dmGraphics::HContext m_GraphicsContext;
        dmResource::HFactory m_Factory;
        dmHID::HContext m_HidContext;
        dmRender::HFontMap m_FontMap;
        dmRender::HRenderContext m_RenderContext;
        dmPlatform::HWindow m_Window;
        uint32_t m_ScreenWidth;
        uint32_t m_ScreenHeight;
    };

    bool Init(Context* context, int argc, char *argv[]);
    int32_t Run(Context* context);
    void Finalize(Context* context);
}

#endif // DM_RENDER_FONTVIEW_H
