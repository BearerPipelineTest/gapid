/*
 * Copyright (C) 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.gapid.image;

import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.gapid.util.Paths.blob;
import static com.google.gapid.util.Paths.imageData;
import static com.google.gapid.util.Paths.imageInfo;
import static com.google.gapid.util.Paths.resourceInfo;
import static com.google.gapid.util.Paths.thumbnail;

import com.google.common.base.Function;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gapid.proto.image.Image.Info;
import com.google.gapid.proto.service.Service;
import com.google.gapid.proto.service.api.API;
import com.google.gapid.proto.service.path.Path;
import com.google.gapid.server.Client;
import com.google.gapid.util.Values;

import org.eclipse.swt.graphics.ImageData;

import java.util.List;
import java.util.function.Consumer;

/**
 * A {@link MultiLevelImage} fetched from the RPC server.
 */
public class FetchedImage implements MultiLevelImage {
  private final Level[] levels;

  public static ListenableFuture<FetchedImage> load(
      Client client, ListenableFuture<Path.ImageInfo> imageInfo) {
    return Futures.transformAsync(imageInfo, imageInfoPath -> load(client, imageInfoPath));
  }

  public static ListenableFuture<FetchedImage> load(Client client, Path.ImageInfo imagePath) {
    return Futures.transformAsync(client.get(imageInfo(imagePath)), value -> {
      Images.Format format = getFormat(value.getImageInfo());
      return Futures.transform(client.get(imageData(imagePath, format.format)),
          pixelValue -> new FetchedImage(client, format, pixelValue.getImageInfo()));
    });
  }

  public static ListenableFuture<FetchedImage> load(Client client, Path.ResourceData imagePath) {
    return Futures.transformAsync(client.get(resourceInfo(imagePath)), value -> {
      API.ResourceData data = value.getResourceData();
      API.Texture texture = data.getTexture();
      switch (texture.getTypeCase()) {
        case TEXTURE_1D: return load(client, imagePath, getFormat(texture.getTexture1D()));
        case TEXTURE_1D_ARRAY: return load(client, imagePath, getFormat(texture.getTexture1DArray()));
        case TEXTURE_2D: return load(client, imagePath, getFormat(texture.getTexture2D()));
        case TEXTURE_2D_ARRAY: return load(client, imagePath, getFormat(texture.getTexture2DArray()));
        case TEXTURE_3D: return load(client, imagePath, getFormat(texture.getTexture3D()));
        case CUBEMAP: return load(client, imagePath, getFormat(texture.getCubemap()));
        case CUBEMAP_ARRAY: return load(client, imagePath, getFormat(texture.getCubemapArray()));
        default:
          throw new UnsupportedOperationException("Unexpected resource type: " + value);
      }
    });
  }

  public static ListenableFuture<FetchedImage> load(
      Client client, Path.ResourceData imagePath, Images.Format format) {
    return Futures.transform(client.get(imageData(imagePath, format.format)), value -> {
      API.ResourceData data = value.getResourceData();
      API.Texture texture = data.getTexture();
      switch (texture.getTypeCase()) {
        case TEXTURE_1D: return new FetchedImage(client, format, texture.getTexture1D());
        case TEXTURE_1D_ARRAY: return new FetchedImage(client, format, texture.getTexture1DArray());
        case TEXTURE_2D: return new FetchedImage(client, format, texture.getTexture2D());
        case TEXTURE_2D_ARRAY: return new FetchedImage(client, format, texture.getTexture2DArray());
        case TEXTURE_3D: return new FetchedImage(client, format, texture.getTexture3D());
        case CUBEMAP: return new FetchedImage(client, format, texture.getCubemap());
        case CUBEMAP_ARRAY: return new FetchedImage(client, format, texture.getCubemapArray());
        default:
          throw new UnsupportedOperationException("Unexpected resource type: " + value);
      }
    });
  }

  public static ListenableFuture<ImageData> loadLevel(
      ListenableFuture<FetchedImage> futureImage, final int level) {
    return Futures.transformAsync(futureImage, image -> Futures.transform(
        image.getLevel(Math.min(level, image.getLevelCount())), (l) -> l.getImageData()));
  }

  public static ListenableFuture<ImageData> loadThumbnail(Client client, Path.Thumbnail path) {
    return loadThumbnail(client, path, i -> {/* do nothing */});
  }

  public static ListenableFuture<ImageData> loadThumbnail(
      Client client, Path.Thumbnail path, Consumer<Info> onInfo) {
    return loadLevel(Futures.transform(client.get(thumbnail(path)), value -> {
      onInfo.accept(value.getImageInfo());
      return new FetchedImage(client, Images.Format.Color8, value.getImageInfo());
    }), 0);
  }

  private static Images.Format getFormat(Info imageInfo) {
    return Images.Format.from(imageInfo.getFormat());
  }

  private static Images.Format getFormat(API.Texture1D texture) {
    return (texture.getLevelsCount() == 0) ? Images.Format.Color8 : getFormat(texture.getLevels(0));
  }

  private static Images.Format getFormat(API.Texture1DArray texture) {
    return (texture.getLayersCount() == 0 || texture.getLayers(0).getLevelsCount() == 0)
        ? Images.Format.Color8 : getFormat(texture.getLayers(0).getLevels(0));
  }

  private static Images.Format getFormat(API.Texture2D texture) {
    return (texture.getLevelsCount() == 0) ? Images.Format.Color8 : getFormat(texture.getLevels(0));
  }

  private static Images.Format getFormat(API.Texture2DArray texture) {
    return (texture.getLayersCount() == 0 || texture.getLayers(0).getLevelsCount() == 0)
        ? Images.Format.Color8 : getFormat(texture.getLayers(0).getLevels(0));
  }

  private static Images.Format getFormat(API.Texture3D texture) {
    return (texture.getLevelsCount() == 0) ? Images.Format.Color8 : getFormat(texture.getLevels(0));
  }

  private static Images.Format getFormat(API.Cubemap cubemap) {
    return (cubemap.getLevelsCount() == 0) ?
        Images.Format.Color8 : getFormat(cubemap.getLevels(0).getNegativeZ());
  }

  private static Images.Format getFormat(API.CubemapArray texture) {
    return (texture.getLayersCount() == 0 || texture.getLayers(0).getLevelsCount() == 0)
        ? Images.Format.Color8 : getFormat(texture.getLayers(0).getLevels(0).getNegativeZ());
  }


  public FetchedImage(Client client, Images.Format format, Info imageInfo) {
    levels = new Level[] { new SingleFacedLevel(client, format, imageInfo) };
  }

  public FetchedImage(Client client, Images.Format format, API.Texture1D texture) {
    List<Info> infos = texture.getLevelsList();
    levels = new Level[infos.size()];
    for (int i = 0; i < infos.size(); i++) {
      levels[i] = new SingleFacedLevel(client, format, infos.get(i));
    }
  }

  public FetchedImage(Client client, Images.Format format, API.Texture1DArray texture) {
    throw new RuntimeException("FetchedImage() for Texture1DArray not yet implemented");
  }

  public FetchedImage(Client client, Images.Format format, API.Texture2D texture) {
    List<Info> infos = texture.getLevelsList();
    levels = new Level[infos.size()];
    for (int i = 0; i < infos.size(); i++) {
      levels[i] = new SingleFacedLevel(client, format, infos.get(i));
    }
  }

  public FetchedImage(Client client, Images.Format format, API.Texture2DArray texture) {
    throw new RuntimeException("FetchedImage() for Texture2DArray not yet implemented");
  }

  public FetchedImage(Client client, Images.Format format, API.Texture3D texture) {
    List<Info> infos = texture.getLevelsList();
    levels = new Level[infos.size()];
    for (int i = 0; i < infos.size(); i++) {
      levels[i] = new SingleFacedLevel(client, format, infos.get(i));
    }
  }

  public FetchedImage(Client client, Images.Format format, API.Cubemap cubemap) {
    List<API.CubemapLevel> infos = cubemap.getLevelsList();
    levels = new Level[infos.size()];
    for (int i = 0; i < infos.size(); i++) {
      levels[i] = new SixFacedLevel(client, format, infos.get(i));
    }
  }

  public FetchedImage(Client client, Images.Format format, API.CubemapArray texture) {
    throw new RuntimeException("FetchedImage() for CubemapArray not yet implemented");
  }

  @Override
  public int getLevelCount() {
    return levels.length;
  }

  @Override
  public ListenableFuture<Image> getLevel(int index) {
    return (index < 0 || index >= levels.length) ?
        immediateFailedFuture(new IllegalArgumentException("Invalid image level " + index)) :
        levels[index].get();
  }

  /**
   * A single mipmap level {@link Image} of a {@link FetchedImage}.
   */
  private abstract static class Level implements Function<Image, Image> {
    public static final Level EMPTY_LEVEL = new Level(null) {
      @Override
      public ListenableFuture<Image> get() {
        return immediateFuture(Image.EMPTY);
      }

      @Override
      protected ListenableFuture<Image> doLoad() {
        return null;
      }
    };

    protected final Images.Format format;
    private Image image;

    public Level(Images.Format format) {
      this.format = format;
    }

    public ListenableFuture<Image> get() {
      Image result;
      synchronized (this) {
        result = image;
      }
      return (result == null) ? Futures.transform(doLoad(), this) : immediateFuture(result);
    }

    @Override
    public Image apply(Image input) {
      synchronized (this) {
        image = input;
      }
      return image;
    }

    protected abstract ListenableFuture<Image> doLoad();

    protected static Image convertImage(Info info, Images.Format format, byte[] data) {
      return format.builder(info.getWidth(), info.getHeight(), info.getDepth())
          .update(data, 0, 0, 0, info.getWidth(), info.getHeight(), info.getDepth())
          .build();
    }

    protected static Image convertImage(Info[] infos, Images.Format format, byte[][] data) {
      assert (infos.length == data.length && infos.length == 6);
      // Typically these are all the same, but let's be safe.
      int width = Math.max(
          Math.max(Math.max(Math.max(Math.max(infos[0].getWidth(), infos[1].getWidth()),
              infos[2].getWidth()), infos[3].getWidth()), infos[4].getWidth()),
          infos[5].getWidth());
      int height =
          Math.max(Math.max(
              Math.max(Math.max(Math.max(infos[0].getHeight(), infos[1].getHeight()),
                  infos[2].getHeight()), infos[3].getHeight()),
              infos[4].getHeight()), infos[5].getHeight());

      // +----+----+----+----+
      // |    | -Y |    |    |
      // +----+----+----+----+
      // | -X | +Z | +X | -Z |
      // +----+----+----+----+
      // |    | +Y |    |    |
      // +----+----+----+----+
      return format.builder(4 * width, 3 * height, 1)
          .update(data[0], 0 * width, 1 * height, 0, infos[0].getWidth(), infos[0].getHeight(), 1) // -X
          .update(data[1], 2 * width, 1 * height, 0, infos[1].getWidth(), infos[1].getHeight(), 1) // +X
          .update(data[2], 1 * width, 2 * height, 0, infos[2].getWidth(), infos[2].getHeight(), 1) // -Y
          .update(data[3], 1 * width, 0 * height, 0, infos[3].getWidth(), infos[3].getHeight(), 1) // +Y
          .update(data[4], 3 * width, 1 * height, 0, infos[4].getWidth(), infos[4].getHeight(), 1) // -Z
          .update(data[5], 1 * width, 1 * height, 0, infos[5].getWidth(), infos[5].getHeight(), 1) // +Z
          .flip()
          .build();
    }
  }

  /**
   * A {@link Level} of a simple 2D texture.
   */
  private static class SingleFacedLevel extends Level {
    private final Client client;
    protected final Info imageInfo;

    public SingleFacedLevel(Client client, Images.Format format, Info imageInfo) {
      super(format);
      this.client = client;
      this.imageInfo = imageInfo;
    }

    @Override
    protected ListenableFuture<Image> doLoad() {
      return Futures.transform(client.get(blob(imageInfo.getBytes())), data ->
        convertImage(imageInfo, format, Values.getBytes(data)));
    }
  }

  /**
   * A {@link Level} of a cubemap texture.
   */
  private static class SixFacedLevel extends Level {
    private final Client client;
    protected final Info[] imageInfos;

    public SixFacedLevel(Client client, Images.Format format, API.CubemapLevel level) {
      super(format);
      this.client = client;
      this.imageInfos = new Info[] {
        level.getNegativeX(), level.getPositiveX(),
        level.getNegativeY(), level.getPositiveY(),
        level.getNegativeZ(), level.getPositiveZ()
      };
    }

    @Override
    protected ListenableFuture<Image> doLoad() {
      @SuppressWarnings("unchecked")
      ListenableFuture<Service.Value>[] futures = new ListenableFuture[imageInfos.length];
      for (int i = 0; i < imageInfos.length; i++) {
        futures[i] = client.get(blob(imageInfos[i].getBytes()));
      }
      return Futures.transform(Futures.allAsList(futures), values -> {
        byte[][] data = new byte[values.size()][];
        for (int i = 0; i < data.length; i++) {
          data[i] = Values.getBytes(values.get(i));
        }
        return convertImage(imageInfos, format, data);
      });
    }
  }
}
