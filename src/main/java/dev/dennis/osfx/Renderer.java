package dev.dennis.osfx;

import dev.dennis.osfx.api.BufferProvider;
import dev.dennis.osfx.api.Client;
import dev.dennis.osfx.api.Sprite;
import dev.dennis.osfx.util.KeyMapping;
import dev.dennis.osfx.util.OsrsAppletStub;
import dev.dennis.osfx.util.OsrsConfig;
import dev.dennis.osfx.util.ResourceUtil;
import org.joml.Matrix4f;
import org.lwjgl.bgfx.*;
import org.lwjgl.glfw.GLFWNativeCocoa;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.glfw.GLFWNativeX11;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Platform;

import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;

import static org.lwjgl.bgfx.BGFX.*;
import static org.lwjgl.bgfx.BGFX.bgfx_shutdown;
import static org.lwjgl.bgfx.BGFXPlatform.bgfx_set_platform_data;
import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFW.glfwTerminate;
import static org.lwjgl.system.MemoryUtil.NULL;

public class Renderer implements Callbacks {
    private static final boolean DEBUG = false;

    private static final String TITLE = "OSFX";

    private static final int RESET = BGFX_RESET_NONE;

    private static final int MIN_WIDTH = 765;

    private static final int MIN_HEIGHT = 503;

    private static final int MAX_WIDTH = 7680;

    private static final int MAX_HEIGHT = 2160;

    private static final BGFXReleaseFunctionCallback releaseMemoryCb = BGFXReleaseFunctionCallback.create(
            (ptr, userData) -> MemoryUtil.nmemFree(ptr)
    );

    private final Client client;

    private final CyclicBarrier barrier;

    private final List<DrawSpriteCommand> drawSpriteCommands;

    private int width;

    private int height;

    private long window;

    private int rendererType;

    private int format;

    private int lastMouseX;

    private int lastMouseY;

    private BGFXVertexLayout layout;

    private static boolean isPointOnCanvas(Canvas canvas, int x, int y) {
        Point loc = canvas.getLocation();
        return x >= loc.x && y >= loc.y && x <= loc.x + canvas.getWidth() && y <= loc.y + canvas.getHeight();
    }

    private static Point translateToCanvas(Canvas canvas, int x, int y) {
        Point loc = canvas.getLocation();
        return new Point(x - loc.x, y - loc.y);
    }

    public Renderer(Client client, int width, int height) {
        this.client = client;
        this.barrier = new CyclicBarrier(2);
        this.drawSpriteCommands = new ArrayList<>();
        this.width = width;
        this.height = height;
    }

    private void startClient(OsrsConfig config) {
        client.setCallbacks(this);
        client.setStub(new OsrsAppletStub(config));
        client.setSize(width, height);
        client.init();
        client.start();
    }

    private void stopClient() {
        // This blocks for 5 seconds
        client.stop();
        client.destroy();
    }

    public void start(OsrsConfig config) throws IOException {
        startClient(config);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            initWindow();
            initBgfx(stack);

            BGFXCaps caps = bgfx_get_caps();

            if (DEBUG) {
                bgfx_set_debug(BGFX_DEBUG_TEXT | BGFX_DEBUG_STATS);
            }

            bgfx_set_view_clear(0,
                    BGFX_CLEAR_COLOR | BGFX_CLEAR_DEPTH,
                    0x000000FF,
                    1.0f,
                    0);


            BGFXTextureInfo textureInfo = BGFXTextureInfo.mallocStack(stack);
            bgfx_calc_texture_size(textureInfo, MAX_WIDTH, MAX_HEIGHT, 1, false, false,
                    1, BGFX_TEXTURE_FORMAT_BGRA8);
            IntBuffer textureData = MemoryUtil.memAllocInt(textureInfo.storageSize() / 4 + 1);

            short program = createProgram("vs_quad", "fs_quad");

            Dimension lastCanvasSize = null;
            short textureId = -1;

            layout = createVertexLayout(false, false, true);

            FloatBuffer orthoBuf = MemoryUtil.memAllocFloat(16);
            Matrix4f ortho = new Matrix4f();

            List<Buffer> buffersToRemove = new ArrayList<>();
            List<Short> texturesToRemove = new ArrayList<>();

            // shouldClose && client not crashed
            while (!glfwWindowShouldClose(window)) {
                sync();

                // start of frame
                glfwPollEvents();

                bgfx_set_view_rect(0, 0, 0, width, height);
                bgfx_dbg_text_clear(0, false);

                for (Buffer buf : buffersToRemove) {
                    MemoryUtil.memFree(buf);
                }
                buffersToRemove.clear();

                for (short texId : texturesToRemove) {
                    bgfx_destroy_texture(texId);
                }
                texturesToRemove.clear();

                for (DrawSpriteCommand command : drawSpriteCommands) {
                    buffersToRemove.add(command.getPixelsBuf());
                }
                drawSpriteCommands.clear();

                sync();

                sync();

                ortho.setOrthoLH(0.0f, width, height, 0.0f, 0.0f, 1.0f, !caps.homogeneousDepth());
                ortho.get(orthoBuf);
                bgfx_set_view_transform(0, null, orthoBuf);

                Canvas canvas = client.getCanvas();
                Point canvasLoc = canvas.getLocation();

                BufferProvider bufferProvider = client.getBufferProvider();
                int[] pixels = bufferProvider.getPixels();
                textureData.clear();
                textureData.put(pixels);
                textureData.flip();

                if (textureId == -1 || !canvas.getSize().equals(lastCanvasSize)) {
                    if (textureId != -1) {
                        bgfx_destroy_texture(textureId);
                    }
                    textureId = bgfx_create_texture_2d(canvas.getWidth(), canvas.getHeight(), false, 1,
                            BGFX_TEXTURE_FORMAT_BGRA8, BGFX_TEXTURE_NONE, null);
                    lastCanvasSize = canvas.getSize();
                }
                bgfx_update_texture_2d(textureId, 0, 0, 0, 0,
                        canvas.getWidth(), canvas.getHeight(), bgfx_make_ref(textureData), 0xFFFF);

                long encoder = bgfx_encoder_begin(false);

                bgfx_encoder_set_texture(encoder, 0, (short) 0, textureId, BGFX_SAMPLER_NONE);

                if (fullscreenTextureEnabled) {
                    renderScreenSpaceQuad(encoder, 0, program, canvasLoc.x, canvasLoc.y,
                            canvas.getWidth(), canvas.getHeight(), false);
                }

                for (DrawSpriteCommand command : drawSpriteCommands) {
                    int x = command.getX();
                    int y = command.getY();
                    int spriteWidth = command.getSpriteWidth();
                    int spriteHeight = command.getSpriteHeight();
                    int width = command.getWidth();
                    int height = command.getHeight();
                    short texId = bgfx_create_texture_2d(spriteWidth, spriteHeight, false, 1,
                            BGFX_TEXTURE_FORMAT_BGRA8, BGFX_TEXTURE_NONE, bgfx_make_ref(command.getPixelsBuf()));
                    texturesToRemove.add(texId);

                    bgfx_encoder_set_scissor(encoder, command.getScissorX(), command.getScissorY(),
                            command.getScissorWidth(), command.getScissorHeight());
                    bgfx_encoder_set_texture(encoder, 0, (short) 0, texId, BGFX_SAMPLER_NONE);
                    renderScreenSpaceQuad(encoder, 0, program, x, y, width, height, true);
                }

                bgfx_encoder_end(encoder);

                bgfx_touch(0);

                bgfx_frame(false);
                sync();

            }

            // data is managed from main thread, and it's passed to renderer
            // just as MemoryRef. At this point the renderer might be using it. We must wait
            // for the previous frame to finish before we can free it.
            bgfx_frame(false);

            MemoryUtil.memFree(textureData);
            MemoryUtil.memFree(orthoBuf);

            layout.free();
            bgfx_destroy_texture(textureId);
            bgfx_destroy_program(program);

            bgfx_shutdown();

            System.out.println("BGFX Shutdown");

            destroyWindow();

            stopClient();

            System.out.println("Destroyed");

            // For some reason the AWT thread keeps running which prevents this process from stopping
            System.exit(0);
        }
    }

    private void initWindow() {
        if (!glfwInit()) {
            throw new RuntimeException("Failed to initialize GLFW");
        }

        // the client (renderer) API is managed by bgfx
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);

        window = glfwCreateWindow(width, height, TITLE, NULL, NULL);

        if (window == NULL) {
            throw new RuntimeException("Error creating GLFW window");
        }

        glfwSetWindowSizeLimits(window, MIN_WIDTH, MIN_HEIGHT, MAX_WIDTH, MAX_HEIGHT);

        setGlfwCallbacks();
    }

    private void destroyWindow() {
        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);
        glfwTerminate();

        System.out.println("GLFW Terminated");
    }

    private void initBgfx(MemoryStack stack) {
        BGFXPlatformData platformData = BGFXPlatformData.callocStack(stack);

        switch (Platform.get()) {
            case LINUX:
                platformData.ndt(GLFWNativeX11.glfwGetX11Display());
                platformData.nwh(GLFWNativeX11.glfwGetX11Window(window));
                break;
            case MACOSX:
                platformData.ndt(NULL);
                platformData.nwh(GLFWNativeCocoa.glfwGetCocoaWindow(window));
                break;
            case WINDOWS:
                platformData.ndt(NULL);
                platformData.nwh(GLFWNativeWin32.glfwGetWin32Window(window));
                break;
        }

        platformData.context(NULL);
        platformData.backBuffer(NULL);
        platformData.backBufferDS(NULL);

        bgfx_set_platform_data(platformData);

        BGFXInit init = BGFXInit.mallocStack(stack);
        bgfx_init_ctor(init);
        init.type(BGFX_RENDERER_TYPE_COUNT)
                .resolution(it -> it
                        .reset(RESET)
                        .width(width)
                        .height(height));

        if (!bgfx_init(init)) {
            throw new RuntimeException("Failed to initialize BGFX");
        }

        BGFXCaps caps = bgfx_get_caps();

        rendererType = caps.rendererType();
        String rendererName = bgfx_get_renderer_name(rendererType);
        System.out.println("Using renderer: " + rendererName);

        format = init.resolution().format();
    }

    @Override
    public void onFrameStart() {
        sync();
        sync();
    }

    @Override
    public void onFrameEnd() {
        sync();
        sync();
    }

    @Override
    public boolean drawSprite(Sprite sprite, int x, int y) {
        return drawSprite(sprite, x, y, sprite.getWidth(), sprite.getHeight());
    }

    @Override
    public boolean drawSprite(Sprite sprite, int x, int y, int width, int height) {
        if (width == 0 || height == 0) {
            return true;
        }
        int spriteWidth = sprite.getWidth();
        int spriteHeight = sprite.getHeight();

        int offsetX = sprite.getOffsetX();
        int offsetY = sprite.getOffsetY();

        if (spriteWidth != width || spriteHeight != height) {
            int scaledX = 0;
            int scaledY = 0;
            final int maxWidth = sprite.getMaxWidth();
            final int maxHeight = sprite.getMaxHeight();
            final int scaledWidth = (maxWidth << 16) / width;
            final int scaledHeight = (maxHeight << 16) / height;
            if (offsetX > 0) {
                final int scaledOffsetX = ((offsetX << 16) + scaledWidth - 1) / scaledWidth;
                x += scaledOffsetX;
                scaledX += scaledOffsetX * scaledWidth - (offsetX << 16);
            }
            if (offsetY > 0) {
                final int scaledOffsetY = ((offsetY << 16) + scaledHeight - 1) / scaledHeight;
                y += scaledOffsetY;
                scaledY += scaledOffsetY * scaledHeight - (offsetY << 16);
            }
            if (spriteWidth < maxWidth) {
                width = ((spriteWidth << 16) - scaledX + scaledWidth - 1) / scaledWidth;
            }
            if (spriteHeight < maxHeight) {
                height = ((spriteHeight << 16) - scaledY + scaledHeight - 1) / scaledHeight;
            }
        } else {
            x += offsetX;
            y += offsetY;
        }

        int[] pixels = sprite.getPixels();
        for (int i = 0; i < pixels.length; i++) {
            if (pixels[i] != 0) {
                pixels[i] |= 0xFF << 24;
            }
        }
        IntBuffer pixelsBuf = MemoryUtil.memAllocInt(pixels.length);
        pixelsBuf.put(pixels);
        pixelsBuf.flip();
        drawSpriteCommands.add(new DrawSpriteCommand(pixelsBuf, spriteWidth, spriteHeight, x, y, width, height,
                client.getScissorX(), client.getScissorY(), client.getScissorWidth(), client.getScissorHeight()));
        return true;
    }

    private void sync() {
        try {
            barrier.await();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private short createShader(String name) throws IOException {
        String path = "shaders/";
        switch (rendererType) {
            case BGFX_RENDERER_TYPE_DIRECT3D9:
                path += "dx9";
                break;
            case BGFX_RENDERER_TYPE_DIRECT3D11:
                path += "dx11";
                break;
            case BGFX_RENDERER_TYPE_OPENGLES:
                path += "essl";
                break;
            case BGFX_RENDERER_TYPE_OPENGL:
                path += "glsl";
                break;
            case BGFX_RENDERER_TYPE_METAL:
                path += "metal";
                break;
            default:
                throw new IllegalStateException("Unknown renderer type: " + rendererType);
        }
        ByteBuffer shaderResource = ResourceUtil.loadResource(path, name + ".bin");
        return bgfx_create_shader(bgfx_make_ref_release(shaderResource, releaseMemoryCb, 0L));
    }

    private short createProgram(String vertexShaderName, String fragmentShaderName) throws IOException {
        return createProgram(vertexShaderName, fragmentShaderName, true);
    }

    private short createProgram(String vertexShaderName, String fragmentShaderName, boolean destroy)
            throws IOException {
        short vs = createShader(vertexShaderName);
        short fs = createShader(fragmentShaderName);
        return bgfx_create_program(vs, fs, destroy);
    }

    private void renderScreenSpaceQuad(long encoder, int view, short program, float x, float y,
                                       float width, float height, boolean alpha) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            BGFXTransientVertexBuffer tvb = BGFXTransientVertexBuffer.callocStack(stack);
            BGFXTransientIndexBuffer tib = BGFXTransientIndexBuffer.callocStack(stack);

            if (bgfx_alloc_transient_buffers(tvb, layout, 4, tib, 6)) {
                ByteBuffer vertex = tvb.data();

                float z = 0.0f;

                float minX = x;
                float minY = y;
                float maxX = x + width;
                float maxY = y + height;

                float minU = 0.0f;
                float minV = 0.0f;
                float maxU = 1.0f;
                float maxV = 1.0f;

                vertex.putFloat(minX);
                vertex.putFloat(minY);
                vertex.putFloat(z);
                vertex.putFloat(minU);
                vertex.putFloat(minV);

                vertex.putFloat(maxX);
                vertex.putFloat(minY);
                vertex.putFloat(z);
                vertex.putFloat(maxU);
                vertex.putFloat(minV);

                vertex.putFloat(maxX);
                vertex.putFloat(maxY);
                vertex.putFloat(z);
                vertex.putFloat(maxU);
                vertex.putFloat(maxV);

                vertex.putFloat(minX);
                vertex.putFloat(maxY);
                vertex.putFloat(z);
                vertex.putFloat(minU);
                vertex.putFloat(maxV);
                vertex.flip();

                ByteBuffer indices = tib.data();
                indices.putShort((short) 0);
                indices.putShort((short) 2);
                indices.putShort((short) 1);
                indices.putShort((short) 0);
                indices.putShort((short) 3);
                indices.putShort((short) 2);
                indices.flip();

                long state = BGFX_STATE_WRITE_RGB | BGFX_STATE_WRITE_A | (alpha ? BGFX_STATE_BLEND_ALPHA : 0);
                bgfx_encoder_set_state(encoder, state, 0);

                bgfx_encoder_set_transient_vertex_buffer(encoder, 0, tvb, 0, 4,
                        BGFX_INVALID_HANDLE);
                bgfx_encoder_set_transient_index_buffer(encoder, tib, 0, 6);

                bgfx_encoder_submit(encoder, view, program, 0, false);
            }
        }
    }

    private BGFXVertexLayout createVertexLayout(boolean withNormals, boolean withColor, boolean withTexCoords) {
        BGFXVertexLayout layout = BGFXVertexLayout.calloc();

        bgfx_vertex_layout_begin(layout, rendererType);

        bgfx_vertex_layout_add(layout,
                BGFX_ATTRIB_POSITION,
                3,
                BGFX_ATTRIB_TYPE_FLOAT,
                false,
                false);

        if (withNormals) {
            bgfx_vertex_layout_add(layout,
                    BGFX_ATTRIB_NORMAL,
                    3,
                    BGFX_ATTRIB_TYPE_FLOAT,
                    false,
                    false);
        }

        if (withColor) {
            bgfx_vertex_layout_add(layout,
                    BGFX_ATTRIB_COLOR0,
                    4,
                    BGFX_ATTRIB_TYPE_UINT8,
                    true,
                    false);
        }

        if (withTexCoords) {
            bgfx_vertex_layout_add(layout,
                    BGFX_ATTRIB_TEXCOORD0,
                    2,
                    BGFX_ATTRIB_TYPE_FLOAT,
                    false,
                    false);
        }

        bgfx_vertex_layout_end(layout);

        return layout;
    }

    private void setGlfwCallbacks() {
        // Keyboard
        glfwSetCharCallback(window, this::onCharInput);
        glfwSetKeyCallback(window, this::onKeyInput);

        // Mouse
        glfwSetCursorPosCallback(window, this::onMouseMoved);
        glfwSetCursorEnterCallback(window, this::onMouseEntered);
        glfwSetMouseButtonCallback(window, this::onMouseClicked);
        glfwSetScrollCallback(window, this::onMouseScrolled);

        glfwSetWindowFocusCallback(window, this::onFocus);
        glfwSetWindowSizeCallback(window, this::resize);
        glfwSetFramebufferSizeCallback(window, this::resize);
        glfwSetWindowRefreshCallback(window, this::refresh);
    }

    private void onCharInput(long window, int codePoint) {
        char[] chars = Character.toChars(codePoint);
        Canvas canvas = client.getCanvas();
        if (canvas != null && chars.length == 1) {
            KeyEvent event = new KeyEvent(canvas, 0, 0, 0, 0, chars[0]);
            for (KeyListener listener : canvas.getKeyListeners()) {
                listener.keyTyped(event);
            }
        }
    }

    private void onKeyInput(long window, int key, int scanCode, int action, int mods) {
        Canvas canvas = client.getCanvas();
        if (canvas != null) {
            key = KeyMapping.mapGlfwKeyToJava(key);
            if (key == -1) {
                return;
            }
            int modifiers = KeyMapping.mapGlfwModifiersToJava(mods);
            KeyEvent event = new KeyEvent(canvas, 0, 0, modifiers, key, '\0');
            for (KeyListener listener : canvas.getKeyListeners()) {
                if (action == GLFW_RELEASE) {
                    listener.keyReleased(event);
                } else {
                    listener.keyPressed(event);
                }
            }
        }
    }

    private void onMouseMoved(long window, double x, double y) {
        Canvas canvas = client.getCanvas();
        if (canvas != null) {
            int realX = (int) x;
            int realY = (int) y;
            Point canvasPoint = translateToCanvas(canvas, realX, realY);
            MouseEvent event = new MouseEvent(canvas, 0, System.currentTimeMillis(), 0,
                    canvasPoint.x, canvasPoint.y, 0, false);
            for (MouseMotionListener listener : canvas.getMouseMotionListeners()) {
                listener.mouseMoved(event);
            }
            lastMouseX = realX;
            lastMouseY = realY;
        }
    }

    private void onMouseEntered(long window, boolean entered) {
        Canvas canvas = client.getCanvas();
        if (canvas != null) {
            double[] xArr = new double[1];
            double[] yArr = new double[1];
            glfwGetCursorPos(window, xArr, yArr);
            int x = (int) xArr[0];
            int y = (int) yArr[0];
            Point canvasPoint = translateToCanvas(canvas, x, y);
            boolean onCanvas = isPointOnCanvas(canvas, x, y);
            boolean wasOnCanvas = isPointOnCanvas(canvas, lastMouseX, lastMouseY);
            MouseEvent event = new MouseEvent(canvas, 0, System.currentTimeMillis(), 0,
                    canvasPoint.x, canvasPoint.y, 0, false);
            for (MouseListener listener : canvas.getMouseListeners()) {
                if (entered && onCanvas) {
                    listener.mouseEntered(event);
                } else if (!entered && wasOnCanvas) {
                    listener.mouseExited(event);
                }
            }
        }
    }

    private void onMouseClicked(long window, int button, int action, int mods) {
        //System.out.println(button + ", " + action + ", " + mods);
        Canvas canvas = client.getCanvas();
        if (canvas != null) {
            double[] x = new double[1];
            double[] y = new double[1];
            glfwGetCursorPos(window, x, y);
            if (!isPointOnCanvas(canvas, (int) x[0], (int) y[0]) && action == GLFW_PRESS) {
                return;
            }
            Point canvasPoint = translateToCanvas(canvas, (int) x[0], (int) y[0]);
            int awtButton = MouseEvent.NOBUTTON;
            if (button == GLFW_MOUSE_BUTTON_LEFT) {
                awtButton = MouseEvent.BUTTON1;
            } else if (button == GLFW_MOUSE_BUTTON_RIGHT) {
                awtButton = MouseEvent.BUTTON3;
            } else if (button == GLFW_MOUSE_BUTTON_MIDDLE) {
                awtButton = MouseEvent.BUTTON2;
            }
            MouseEvent event = new MouseEvent(canvas, 0, System.currentTimeMillis(), 0,
                    canvasPoint.x, canvasPoint.y, 1, false, awtButton);
            for (MouseListener listener : canvas.getMouseListeners()) {
                if (action == GLFW_PRESS) {
                    listener.mousePressed(event);
                } else {
                    listener.mouseReleased(event);
                }
            }
        }
    }

    private void onMouseScrolled(long window, double xOffset, double yOffset) {
        Canvas canvas = client.getCanvas();
        if (canvas != null) {
            double[] x = new double[1];
            double[] y = new double[1];
            glfwGetCursorPos(window, x, y);
            if (!isPointOnCanvas(canvas, (int) x[0], (int) y[0])) {
                return;
            }
            MouseWheelEvent event = new MouseWheelEvent(canvas, 0, 0, 0, 0, 0, 0,
                    false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 0, (int) -yOffset);
            for (MouseWheelListener listener : canvas.getMouseWheelListeners()) {
                listener.mouseWheelMoved(event);
            }
        }
    }

    private void onFocus(long window, boolean focused) {
        Canvas canvas = client.getCanvas();
        if (canvas != null) {
            for (FocusListener listener : canvas.getFocusListeners()) {
                if (focused) {
                    listener.focusGained(null);
                } else {
                    listener.focusLost(null);
                }
            }
        }
    }

    private void resize(long window, int width, int height) {
        if (width < MIN_WIDTH || height < MIN_HEIGHT || (this.width == width && this.height == height)) {
            return;
        }
        System.out.println("Resize " + width + ", " + height);
        this.width = width;
        this.height = height;
        client.setSize(width, height);
        bgfx_reset(width, height, RESET, format);
    }

    private void refresh(long window) {
        System.out.println("Refresh");
    }
}
